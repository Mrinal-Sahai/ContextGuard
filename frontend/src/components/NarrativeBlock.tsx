
export const NarrativeBlock: React.FC<{ title: string; content: string; accentColor: string; textContent: string }> = ({ title, content, accentColor, textContent }) => {
  return (
    <div>
      <h4 className={`text-sm font-semibold ${accentColor} mb-2`}>{title}</h4>
      <p className={`text-sm ${textContent} leading-relaxed whitespace-pre-line`}>{content}</p>
    </div>
  );
};
