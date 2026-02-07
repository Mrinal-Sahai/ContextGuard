// src/components/ReviewerChecklist.tsx

import React, { useState } from 'react';
import { type RiskAssessment, type DiffMetrics } from '../types';
import './ReviewerChecklist.css';

interface ReviewerChecklistProps {
  risk: RiskAssessment;
  metrics: DiffMetrics;
}

/**
 * Auto-generated reviewer checklist based on risk factors.
 * 
 * WHY THIS EXISTS:
 * - Converts intelligence into ACTION ITEMS
 * - Reduces cognitive load (reviewer knows what to check)
 * - Checklist is generated, not hardcoded (adapts to PR)
 * 
 * CHECKLIST GENERATION LOGIC:
 * - If critical files touched → Add security/auth checks
 * - If high complexity delta → Add logic verification checks
 * - If large LOC change → Add test coverage checks
 * 
 * NOTE: Checkboxes are CLIENT-SIDE ONLY (not persisted)
 */
const ReviewerChecklist: React.FC<ReviewerChecklistProps> = ({ risk, metrics }) => {
  const [checkedItems, setCheckedItems] = useState<Set<number>>(new Set());

  const toggleItem = (index: number) => {
    setCheckedItems(prev => {
      const newSet = new Set(prev);
      if (newSet.has(index)) {
        newSet.delete(index);
      } else {
        newSet.add(index);
      }
      return newSet;
    });
  };

  // Generate checklist items based on risk factors
  const checklistItems = generateChecklist(risk, metrics);

  return (
    <section className="reviewer-checklist">
      <h2 className="section-title">Reviewer Checklist</h2>

      <div className="checklist-items">
        {checklistItems.map((item, index) => (
          <div
            key={index}
            className={`checklist-item ${checkedItems.has(index) ? 'checked' : ''}`}
            onClick={() => toggleItem(index)}
          >
            <input
              type="checkbox"
              checked={checkedItems.has(index)}
              readOnly
              className="checklist-checkbox"
            />
            <label className="checklist-label">{item}</label>
          </div>
        ))}
      </div>

      <div className="checklist-progress">
        {checkedItems.size} of {checklistItems.length} items checked
      </div>
    </section>
  );
};

/**
 * Generate checklist items based on PR characteristics.
 * 
 * LOGIC:
 * - Base items (always present)
 * - Conditional items based on risk factors
 */
const generateChecklist = (risk: RiskAssessment, metrics: DiffMetrics): string[] => {
  const items: string[] = [];

  // Always check code quality
  items.push('Review code for adherence to team style guidelines');

  // Critical files checks
  if (risk.criticalFilesDetected.length > 0) {
    const isSecurity = risk.criticalFilesDetected.some(f =>
      f.toLowerCase().includes('auth') ||
      f.toLowerCase().includes('security') ||
      f.toLowerCase().includes('token')
    );

    if (isSecurity) {
      items.push('Verify authentication/authorization logic handles edge cases');
      items.push('Check for hardcoded secrets or credentials');
      items.push('Ensure error handling does not leak sensitive information');
    }

    const isPayment = risk.criticalFilesDetected.some(f =>
      f.toLowerCase().includes('payment') ||
      f.toLowerCase().includes('transaction')
    );

    if (isPayment) {
      items.push('Verify payment transaction handling is idempotent');
      items.push('Check error handling and rollback mechanisms');
    }
  }

  // High complexity checks
  if (metrics.complexityDelta > 15) {
    items.push('Review complex logic for potential bugs or edge cases');
    items.push('Ensure adequate unit test coverage for new logic paths');
  }

  // Large change checks
  if (metrics.linesAdded > 300) {
    items.push('Verify that PR is not mixing multiple unrelated changes');
    items.push('Check if integration tests cover new functionality');
  }

  // Database/migration checks
  const hasMigration = metrics.fileChanges.some(f =>
    f.filename.toLowerCase().includes('migration') ||
    f.filename.toLowerCase().includes('schema')
  );

  if (hasMigration) {
    items.push('Review database migration for backward compatibility');
    items.push('Ensure migration has rollback strategy');
  }

  // Config changes
  const hasConfig = metrics.fileChanges.some(f =>
    f.filename.toLowerCase().includes('config') ||
    f.filename.toLowerCase().includes('application.properties') ||
    f.filename.toLowerCase().includes('application.yml')
  );

  if (hasConfig) {
    items.push('Verify configuration changes are documented');
    items.push('Ensure backward compatibility with existing deployments');
  }

  // Always check tests
  items.push('Verify test coverage is adequate for changes');
  items.push('Check that all tests pass in CI/CD pipeline');

  return items;
};

export default ReviewerChecklist;