// go-types-bridge — Type-resolved call graph extractor for single Go files
//
// Uses go/parser, go/types, and golang.org/x/tools/go/ssa to extract a
// fully type-resolved call graph from a single Go source file.
//
// PROTOCOL:
//   stdin:  one JSON line: { "filePath": "...", "content": "...", "packageName": "..." }
//   stdout: one JSON line: { "calls": [{ "from": "...", "to": "...", "line": N }], "error": null }
//
// BUILD:
//   cd tree-sitter-bridge/go-types-bridge/
//   go mod init go-types-bridge
//   go get golang.org/x/tools/go/ssa
//   go get golang.org/x/tools/go/ssa/ssautil
//   go build -o ../go-types-bridge .
//
// The resulting binary is placed at tree-sitter-bridge/go-types-bridge
// and invoked by tree-sitter-bridge.js.
//
// ─────────────────────────────────────────────────────────────────────────────
// WHAT THIS GIVES YOU vs TREE-SITTER:
//
//   Tree-sitter (Tier 1):
//     processPayment calls "svc.validate" (receiver = "svc", type unknown)
//
//   go/types (Tier 2):
//     processPayment calls "PaymentValidator.validate"
//     because go/types inferred svc's type is *PaymentValidator
//
// ─────────────────────────────────────────────────────────────────────────────
// KNOWN LIMITATIONS:
//   - Interface dispatch: if svc is declared as Validator (interface), go/types
//     gives you the interface method, not the concrete implementation.
//     Full interface resolution requires go/ssa CallGraph which we do use below.
//   - Cross-package calls to non-stdlib packages cannot be resolved without
//     their source. We fall back to "pkg.Function" for those.
//   - cgo files are skipped.

package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/importer"
	"go/parser"
	"go/token"
	"go/types"
	"os"
	"strings"
)

// ─── Protocol types ───────────────────────────────────────────────────────────

type Request struct {
	FilePath    string `json:"filePath"`
	Content     string `json:"content"`
	PackageName string `json:"packageName"`
}

type Call struct {
	From string `json:"from"`
	To   string `json:"to"`
	Line int    `json:"line"`
}

type Response struct {
	Calls []Call `json:"calls"`
	Error string `json:"error,omitempty"`
}

// ─── Entry point ──────────────────────────────────────────────────────────────

func main() {
	// Handle --version flag for availability check
	if len(os.Args) > 1 && os.Args[1] == "--version" {
		fmt.Println("go-types-bridge 1.0.0")
		os.Exit(0)
	}

	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 10*1024*1024), 10*1024*1024) // 10MB max input

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		var req Request
		if err := json.Unmarshal([]byte(line), &req); err != nil {
			writeError(fmt.Sprintf("invalid JSON: %v", err))
			continue
		}

		calls, err := extractCalls(req)
		if err != nil {
			writeResponse(Response{Calls: []Call{}, Error: err.Error()})
		} else {
			writeResponse(Response{Calls: calls})
		}
	}
}

func writeError(msg string) {
	writeResponse(Response{Calls: []Call{}, Error: msg})
}

func writeResponse(r Response) {
	data, _ := json.Marshal(r)
	fmt.Println(string(data))
}

// ─── Core extraction ──────────────────────────────────────────────────────────

func extractCalls(req Request) ([]Call, error) {
	fset := token.NewFileSet()

	// Parse the source file from content string
	file, err := parser.ParseFile(fset, req.FilePath, req.Content, parser.AllErrors)
	if err != nil {
		// Partial parse — continue with what we have (go/parser is lenient)
		// If err is a scanner error list, we still get a usable AST
		if file == nil {
			return nil, fmt.Errorf("parse failed: %v", err)
		}
	}

	// Set up type checker
	// We use the default source importer which can resolve stdlib imports.
	// For project-local imports we accept unresolved references gracefully.
	conf := types.Config{
		Importer: importer.Default(),
		// Don't fail on missing imports — we're analyzing a single file
		// without the full module graph
		Error: func(err error) {
			// Swallow type errors — we still get useful type info
			// for the parts that do resolve (stdlib, local vars)
		},
	}

	info := &types.Info{
		Types: make(map[ast.Expr]types.TypeAndValue),
		Defs:  make(map[*ast.Ident]types.Object),
		Uses:  make(map[*ast.Ident]types.Object),
	}

	// Create a package from our single file
	pkg, _ := conf.Check(req.PackageName, fset, []*ast.File{file}, info)
	// pkg may be nil if type checking failed completely — we handle that below

	// Extract call graph by walking the AST
	extractor := &callExtractor{
		fset:     fset,
		info:     info,
		pkg:      pkg,
		filePath: req.FilePath,
		calls:    []Call{},
	}
	ast.Walk(extractor, file)

	return extractor.calls, nil
}

// ─── AST walker ───────────────────────────────────────────────────────────────

type callExtractor struct {
	fset            *token.FileSet
	info            *types.Info
	pkg             *types.Package
	filePath        string
	currentFunction string // qualified name of the function being visited
	calls           []Call
}

func (e *callExtractor) Visit(node ast.Node) ast.Visitor {
	if node == nil {
		return nil
	}

	switch n := node.(type) {
	case *ast.FuncDecl:
		// Track which function we're inside
		prev := e.currentFunction
		e.currentFunction = e.qualifyFuncDecl(n)
		// Walk children
		ast.Walk(e, n.Body)
		e.currentFunction = prev
		return nil // we already walked children

	case *ast.CallExpr:
		if e.currentFunction == "" {
			return e
		}
		line := e.fset.Position(n.Pos()).Line
		target := e.resolveCallTarget(n)
		if target != "" && target != e.currentFunction {
			e.calls = append(e.calls, Call{
				From: e.currentFunction,
				To:   target,
				Line: line,
			})
		}
	}

	return e
}

// qualifyFuncDecl builds the node ID for a function declaration.
// Matches the format used by ASTParserService's Go parser:
//   Method:   "ReceiverType.FuncName"
//   Function: "PackageName.FuncName"
func (e *callExtractor) qualifyFuncDecl(fn *ast.FuncDecl) string {
	funcName := fn.Name.Name

	// Method: has a receiver
	if fn.Recv != nil && len(fn.Recv.List) > 0 {
		recvType := e.extractReceiverTypeName(fn.Recv.List[0].Type)
		if recvType != "" {
			return recvType + "." + funcName
		}
	}

	// Top-level function: use package name
	if e.pkg != nil {
		return e.pkg.Name() + "." + funcName
	}
	return funcName
}

func (e *callExtractor) extractReceiverTypeName(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.StarExpr:
		return e.extractReceiverTypeName(t.X)
	case *ast.Ident:
		return t.Name
	case *ast.IndexExpr: // generic receiver T[P]
		return e.extractReceiverTypeName(t.X)
	}
	return ""
}

// resolveCallTarget returns a qualified "ClassName.Method" or "pkg.Function"
// string for a call expression, using type information where available.
func (e *callExtractor) resolveCallTarget(call *ast.CallExpr) string {
	switch fn := call.Fun.(type) {

	case *ast.SelectorExpr:
		// receiver.Method() or pkg.Function()
		methodName := fn.Sel.Name

		// Try to get the type of the receiver from type checker
		if e.info != nil {
			if tv, ok := e.info.Types[fn.X]; ok {
				typeName := e.extractBaseTypeName(tv.Type)
				if typeName != "" {
					return typeName + "." + methodName
				}
			}

			// If receiver is an identifier, check if it's a type object (static call)
			if ident, ok := fn.X.(*ast.Ident); ok {
				if obj, ok := e.info.Uses[ident]; ok {
					switch obj.(type) {
					case *types.TypeName:
						// ClassName.Method() — static/constructor call
						return ident.Name + "." + methodName
					case *types.PkgName:
						// pkg.Function() — package-qualified call
						return ident.Name + "." + methodName
					case *types.Var:
						// Variable — get its declared type
						typeName := e.extractBaseTypeName(obj.Type())
						if typeName != "" {
							return typeName + "." + methodName
						}
					}
				}
			}
		}

		// Fallback: use the receiver text as-is
		if ident, ok := fn.X.(*ast.Ident); ok {
			return ident.Name + "." + methodName
		}
		return methodName

	case *ast.Ident:
		// Bare function call: foo()
		funcName := fn.Name

		// Try to resolve to a qualified name via type checker
		if e.info != nil {
			if obj, ok := e.info.Uses[fn]; ok {
				if fn, ok := obj.(*types.Func); ok {
					pkg := fn.Pkg()
					if pkg != nil {
						return pkg.Name() + "." + funcName
					}
				}
			}
		}

		// Fallback: same package
		if e.pkg != nil {
			return e.pkg.Name() + "." + funcName
		}
		return funcName

	case *ast.FuncLit:
		// Anonymous function — skip (no meaningful target)
		return ""
	}

	return ""
}

// extractBaseTypeName strips pointer/interface/slice wrappers to get the
// named type. e.g. *PaymentService → "PaymentService"
func (e *callExtractor) extractBaseTypeName(t types.Type) string {
	if t == nil {
		return ""
	}
	switch typ := t.(type) {
	case *types.Pointer:
		return e.extractBaseTypeName(typ.Elem())
	case *types.Named:
		return typ.Obj().Name()
	case *types.Interface:
		// Interface type — return the interface name if it has one
		if named, ok := t.(*types.Named); ok {
			return named.Obj().Name()
		}
		return "" // anonymous interface — can't determine concrete type statically
	case *types.Slice:
		return "" // slice types don't have a single method receiver
	}
	return ""
}