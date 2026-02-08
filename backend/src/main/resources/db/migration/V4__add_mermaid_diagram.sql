-- Add diagram columns
ALTER TABLE pr_analysis_results
ADD COLUMN mermaid_diagram TEXT,
ADD COLUMN diagram_verification_notes TEXT,
ADD COLUMN diagram_metrics JSONB;

-- Create index for faster retrieval
CREATE INDEX idx_pr_analysis_diagram ON pr_analysis_results(id)
WHERE mermaid_diagram IS NOT NULL;

-- Add comments
COMMENT ON COLUMN pr_analysis_results.mermaid_diagram IS 'Generated Mermaid diagram string with method-level call graph';
COMMENT ON COLUMN pr_analysis_results.diagram_verification_notes IS 'AST analysis verification notes and language detection info';
COMMENT ON COLUMN pr_analysis_results.diagram_metrics IS 'Graph metrics (nodes, edges, complexity, hotspots) as JSON';