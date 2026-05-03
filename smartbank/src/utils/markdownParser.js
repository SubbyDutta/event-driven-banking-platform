/**
 * Simple markdown parser for chatbot messages
 * Converts markdown syntax to HTML for display
 */

export function parseMarkdown(text) {
  if (!text) return '';
  
  let html = text;
  
  // Convert **bold** to <strong>bold</strong>
  html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
  
  // Convert *italic* to <em>italic</em> (but not bullet points)
  html = html.replace(/\*([^*\n]+?)\*/g, '<em>$1</em>');
  
  // Convert bullet points at start of line (* text) to proper list items
  const lines = html.split('\n');
  let inList = false;
  const processedLines = [];
  
  lines.forEach((line) => {
    const trimmedLine = line.trim();
    
    // Check if line starts with * (bullet point)
    if (trimmedLine.match(/^\*\s+(.+)/)) {
      const content = trimmedLine.replace(/^\*\s+/, '');
      if (!inList) {
        processedLines.push('<ul style="margin: 0.5rem 0; padding-left: 1.5rem;">');
        inList = true;
      }
      processedLines.push(`<li style="margin: 0.25rem 0;">${content}</li>`);
    } else {
      if (inList) {
        processedLines.push('</ul>');
        inList = false;
      }
      if (trimmedLine) {
        processedLines.push(line);
      } else {
        processedLines.push('<br/>');
      }
    }
  });
  
  if (inList) {
    processedLines.push('</ul>');
  }
  
  html = processedLines.join('\n');
  
  // Convert line breaks to <br> tags (except where we already have HTML)
  html = html.replace(/\n(?!<)/g, '<br/>');
  
  return html;
}
