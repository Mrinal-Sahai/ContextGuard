import React, { useMemo, useState, useEffect, type JSX } from 'react';

/**
 * CompactRiskTree.tsx
 *
 * PURPOSE
 * A clean, compact, no-nonsense file tree for dashboards.
 * - Normal hierarchical tree (folders + files)
 * - High-risk files prioritized visually and in ordering
 * - Aggregated risk counters at TOP + per folder
 * - Designed for very small dashboard panels
 *
 * UX PRINCIPLES
 * - Zero visual noise
 * - No gradients, no gimmicks
 * - Everything important visible without expanding too much
 */

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export interface FileChangeSummary {
  filename: string;
  linesAdded: number;
  linesDeleted: number;
  riskLevel: RiskLevel;
}

interface Props {
  files: FileChangeSummary[];
  criticalFiles?: string[];
  maxHeight?: number | string;
  onOpen?: (file: FileChangeSummary) => void;
}

/* ---------------- styles (intentionally minimal) ---------------- */
const injectStyles = () => {
  const id = 'compact-risk-tree-styles';
  if (document.getElementById(id)) return;

  const css = `
  .crt { font-family: Inter, system-ui, sans-serif; font-size:12px; color:#0f172a; }
  .crt .container { border:1px solid #e5e7eb; border-radius:6px; background:#fff; }
  .crt .summary { display:flex; gap:8px; padding:6px 8px; border-bottom:1px solid #e5e7eb; background:#fafafa; }
  .crt .pill { padding:2px 6px; border-radius:999px; font-weight:600; }
  .crt .pill.high { background:#fee2e2; color:#991b1b; }
  .crt .pill.medium { background:#fef3c7; color:#92400e; }
  .crt .pill.low { background:#dcfce7; color:#065f46; }

  .crt .tree { overflow:auto; }

  .crt .row { display:flex; align-items:center; gap:6px; padding:4px 8px; white-space:nowrap; }
  .crt .row.folder { font-weight:600; cursor:pointer; }
  .crt .row.file { font-weight:400; }

  .crt .chev { width:12px; text-align:center; color:#64748b; }
  .crt .chev.open { transform:rotate(90deg); }

  .crt .name { overflow:hidden; text-overflow:ellipsis; }
  .crt .meta { margin-left:auto; display:flex; gap:6px; align-items:center; }

  .crt .badge { font-size:10px; font-weight:700; padding:2px 5px; border-radius:999px; }
  .crt .badge.HIGH { background:#fee2e2; color:#991b1b; }
  .crt .badge.MEDIUM { background:#fef3c7; color:#92400e; }
  .crt .badge.LOW { background:#dcfce7; color:#065f46; }

  .crt .counts { font-size:10px; color:#6b7280; }
  `;

  const style = document.createElement('style');
  style.id = id;
  style.innerHTML = css;
  document.head.appendChild(style);
};

/* ---------------- tree model ---------------- */
interface FolderNode {
  type: 'folder';
  name: string;
  path: string;
  children: TreeNode[];
  high: number;
  medium: number;
  low: number;
}

interface FileNode {
  type: 'file';
  name: string;
  path: string;
  file: FileChangeSummary;
}

type TreeNode = FolderNode | FileNode;

const riskWeight = { HIGH: 0, MEDIUM: 1, LOW: 2 };

const buildTree = (files: FileChangeSummary[], critical: Set<string>): FolderNode => {
  const root: FolderNode = { type:'folder', name:'<root>', path:'', children:[], high:0, medium:0, low:0 };

  const bump = (n: FolderNode, r: RiskLevel) => {
    if (r === 'HIGH') n.high++; else if (r === 'MEDIUM') n.medium++; else n.low++;
  };

  for (const f of files) {
    const risk: RiskLevel = critical.has(f.filename) ? 'HIGH' : f.riskLevel;
    const parts = f.filename.split('/');
    let cur = root;
    bump(cur, risk);

    parts.forEach((p, i) => {
      const isFile = i === parts.length - 1;
      if (isFile) {
        cur.children.push({ type:'file', name:p, path:f.filename, file:{ ...f, riskLevel:risk } });
      } else {
        let child = cur.children.find(c => c.type==='folder' && c.name===p) as FolderNode | undefined;
        if (!child) {
          child = { type:'folder', name:p, path: parts.slice(0,i+1).join('/'), children:[], high:0, medium:0, low:0 };
          cur.children.push(child);
        }
        bump(child, risk);
        cur = child;
      }
    });
  }

  const sort = (n: FolderNode) => {
    n.children.sort((a,b) => {
      if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
      if (a.type === 'folder' && b.type === 'folder') {
        if (a.high !== b.high) return b.high - a.high;
        return a.name.localeCompare(b.name);
      }
      if (a.type === 'file' && b.type === 'file') {
        const ra = riskWeight[a.file.riskLevel];
        const rb = riskWeight[b.file.riskLevel];
        if (ra !== rb) return ra - rb;
        return a.name.localeCompare(b.name);
      }
      return 0;
    });
    n.children.forEach(c => c.type === 'folder' && sort(c));
  };

  sort(root);
  return root;
};

/* ---------------- component ---------------- */
const CompactRiskTree: React.FC<Props> = ({ files, criticalFiles = [], maxHeight = 360, onOpen }) => {
  useEffect(injectStyles, []);

  const critical = useMemo(() => new Set(criticalFiles), [criticalFiles]);
  const tree = useMemo(() => buildTree(files, critical), [files, critical]);

  const [open, setOpen] = useState<Set<string>>(() => {
    const s = new Set<string>();
    tree.children.forEach(c => c.type==='folder' && c.high>0 && s.add(c.path));
    return s;
  });

  const toggle = (p: string) => setOpen(o => {
    const n = new Set(o);
    n.has(p) ? n.delete(p) : n.add(p);
    return n;
  });

  const renderNode = (n: TreeNode, depth = 0): JSX.Element => {
    if (n.type === 'folder') {
      const expanded = open.has(n.path);
      return (
        <div key={n.path}>
          <div className="row folder" style={{ paddingLeft: depth * 10 }} onClick={() => toggle(n.path)}>
            <span className={`chev ${expanded ? 'open' : ''}`}>▶</span>
            <span className="name">{n.name}</span>
            <span className="counts">H:{n.high} M:{n.medium} L:{n.low}</span>
          </div>
          {expanded && n.children.map(c => renderNode(c, depth + 1))}
        </div>
      );
    }

    return (
      <div key={n.path} className="row file" style={{ paddingLeft: depth * 10 + 12 }} onDoubleClick={() => onOpen?.(n.file)}>
        <span className={`badge ${n.file.riskLevel}`}>{n.file.riskLevel}</span>
        <span className="name">{n.name}</span>
        <span className="meta">+{n.file.linesAdded} -{n.file.linesDeleted}</span>
      </div>
    );
  };

  return (
    <div className="crt" style={{ maxHeight }}>
      <div className="container">
        <div className="summary">
          <span className="pill high">High {tree.high}</span>
          <span className="pill medium">Medium {tree.medium}</span>
          <span className="pill low">Low {tree.low}</span>
        </div>
        <div className="tree">
          {tree.children.map(c => renderNode(c, 0))}
        </div>
      </div>
    </div>
  );
};

export default CompactRiskTree;
