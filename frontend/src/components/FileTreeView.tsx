// src/components/FileTreeView.tsx

import React, { useState } from 'react';
import {type JSX } from 'react';
import { type FileChangeSummary } from '../types';
import './FileTreeView.css';

interface FileTreeViewProps {
  files: FileChangeSummary[];
  criticalFiles: string[];
}

/**
 * Hierarchical file tree with risk color-coding.
 * 
 * WHY THIS EXISTS:
 * - Mimics IDE file explorer (familiar to developers)
 * - Color-coding draws attention to high-risk files
 * - Expandable folders reduce visual clutter
 * 
 * RISK COLOR LOGIC:
 * - Red: File is in criticalFiles list OR complexityDelta > 10
 * - Yellow: complexityDelta 5-10
 * - Green: complexityDelta < 5
 */
const FileTreeView: React.FC<FileTreeViewProps> = ({ files, criticalFiles }) => {
  const [expanded, setExpanded] = useState<Set<string>>(new Set(['src']));

  // Build file tree structure
  const tree = buildFileTree(files);

  const toggleFolder = (path: string) => {
    setExpanded(prev => {
      const newSet = new Set(prev);
      if (newSet.has(path)) {
        newSet.delete(path);
      } else {
        newSet.add(path);
      }
      return newSet;
    });
  };

  const getRiskLevel = (file: FileChangeSummary): 'critical' | 'medium' | 'low' => {
    if (criticalFiles.includes(file.filename) || file.complexityDelta > 10) {
      return 'critical';
    }
    if (file.complexityDelta >= 5) {
      return 'medium';
    }
    return 'low';
  };

  const renderTree = (node: TreeNode, depth: number = 0): JSX.Element => {
    if (node.type === 'file') {
      const riskLevel = getRiskLevel(node.file!);
      return (
        <div
          key={node.path}
          className={`file-item ${riskLevel}`}
          style={{ paddingLeft: `${depth * 20}px` }}
        >
          <span className="file-icon">{getFileIcon(riskLevel)}</span>
          <span className="file-name">{node.name}</span>
          <span className="file-stats">
            +{node.file!.linesAdded} -{node.file!.linesDeleted}
          </span>
        </div>
      );
    }

    const isExpanded = expanded.has(node.path);

    return (
      <div key={node.path}>
        <div
          className="folder-item"
          style={{ paddingLeft: `${depth * 20}px` }}
          onClick={() => toggleFolder(node.path)}
        >
          <span className="folder-icon">{isExpanded ? '📂' : '📁'}</span>
          <span className="folder-name">{node.name}</span>
        </div>
        {isExpanded && node.children?.map(child => renderTree(child, depth + 1))}
      </div>
    );
  };

  return (
    <div className="file-tree-view">
      {renderTree(tree)}
    </div>
  );
};

// Helper: Build tree structure from flat file list
interface TreeNode {
  name: string;
  path: string;
  type: 'file' | 'folder';
  children?: TreeNode[];
  file?: FileChangeSummary;
}

const buildFileTree = (files: FileChangeSummary[]): TreeNode => {
  const root: TreeNode = { name: 'root', path: '', type: 'folder', children: [] };

  files.forEach(file => {
    const parts = file.filename.split('/');
    let current = root;

    parts.forEach((part, index) => {
      const isFile = index === parts.length - 1;
      const path = parts.slice(0, index + 1).join('/');

      let child = current.children?.find(c => c.name === part);

      if (!child) {
        child = {
          name: part,
          path,
          type: isFile ? 'file' : 'folder',
          children: isFile ? undefined : [],
          file: isFile ? file : undefined,
        };
        current.children!.push(child);
      }

      if (!isFile) {
        current = child;
      }
    });
  });

  return root;
};

const getFileIcon = (risk: 'critical' | 'medium' | 'low'): string => {
  switch (risk) {
    case 'critical': return '🔴';
    case 'medium': return '🟡';
    case 'low': return '🟢';
  }
};

export default FileTreeView;