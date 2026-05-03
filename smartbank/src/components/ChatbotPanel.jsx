import React, { useState, useRef, useEffect } from 'react';
import API from '../api';
import { parseMarkdown } from '../utils/markdownParser';

const SAMPLE_QUERIES = [
  'What is my account balance?',
  'Show my recent transactions',
  'What is my loan status?',
  'Draft my account summary',
  'How do I transfer money?',
  'Why was my loan rejected?',
];

export default function ChatbotPanel({ username }) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const send = async (e) => {
    e?.preventDefault();
    if (!input.trim() || loading) return;

    const userMsg = { role: 'user', text: input, time: new Date().toISOString() };
    setMessages((m) => [...m, userMsg]);
    setInput('');
    setLoading(true);

    try {
      const res = await API.post('/chatbot', { query: userMsg.text });
      const { type, message } = res.data || {};
      let finalText = message || 'No response received.';
      if (type === 'BLOCKED') finalText = 'This query is not allowed for security reasons.';

      setMessages((m) => [
        ...m,
        { role: 'bot', text: String(finalText), meta: type, time: new Date().toISOString() },
      ]);
    } catch (err) {
      console.error('Chatbot error:', err);
      setMessages((m) => [
        ...m,
        { role: 'bot', text: 'Advisor unavailable. Please try again later.', time: new Date().toISOString() },
      ]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <div className="sb-chat-section" style={{ minHeight: 'calc(100vh - 140px)' }}>
        <div className="sb-chat-side">
          <h4>Suggested Threads</h4>
          <div className="sub">Ask anything</div>
          {SAMPLE_QUERIES.slice(0, 3).map((q, i) => (
            <div key={i} className="item" onClick={() => setInput(q)}>
              <div className="when">PROMPT · {String(i + 1).padStart(2, '0')}</div>
              <div>{q}</div>
            </div>
          ))}
        </div>

        <div className="sb-chat-main">
          <div className="sb-chat-head">
            <div className="title-row">
              <div className="av">S</div>
              <div>
                <div className="title">SubbyBank Advisor</div>
                <div className="stat">Trained on your account · Read-only access</div>
              </div>
            </div>
            <div style={{ fontSize: 11, color: 'var(--muted)', letterSpacing: '0.04em' }}>
              SESSION · <span className="sb-mono">{username?.slice(0, 8) || 'GUEST'}</span>
            </div>
          </div>

          <div className="sb-chat-body">
            {messages.length === 0 ? (
              <>
                <div className="sb-greet">
                  <div className="h">
                    Hello, {username || 'there'}. <em>How can I help?</em>
                  </div>
                  <div className="p">
                    Ask me about your balance, transactions, loans, or transfers. I have read-only access
                    to your accounts and can draft summaries, schedule actions, and explain anything in
                    plain language.
                  </div>
                </div>

                <div
                  style={{
                    fontSize: 11,
                    letterSpacing: '0.12em',
                    color: 'var(--muted)',
                    textTransform: 'uppercase',
                    marginTop: 24,
                  }}
                >
                  Suggested prompts
                </div>
                <div className="sb-chip-grid">
                  {SAMPLE_QUERIES.map((q, i) => (
                    <button key={i} className="sb-chip" onClick={() => setInput(q)}>
                      <span className="num">{String(i + 1).padStart(2, '0')}</span>
                      {q}
                    </button>
                  ))}
                </div>
              </>
            ) : (
              messages.map((m, i) => (
                <div key={i} className={`sb-msg-row ${m.role}`}>
                  <div className={`sb-msg-bubble ${m.role}`}>
                    <div dangerouslySetInnerHTML={{ __html: parseMarkdown(m.text) }} />
                    {m.meta && (
                      <div className="sb-msg-meta">
                        {m.meta === 'DIRECT' && (
                          <>
                            <i className="bi bi-shield-lock" />
                            Secure data
                          </>
                        )}
                        {m.meta === 'RAG' && (
                          <>
                            <i className="bi bi-bar-chart" />
                            AI summary
                          </>
                        )}
                        {m.meta === 'GEN' && (
                          <>
                            <i className="bi bi-stars" />
                            AI generated
                          </>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              ))
            )}

            {loading && (
              <div className="sb-msg-row bot">
                <div className="sb-msg-bubble bot">
                  <span className="sb-spinner" /> Advisor is thinking…
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          <form onSubmit={send} className="sb-chat-input">
            <input
              placeholder="Ask SubbyBank — e.g. 'Show outflows from last week'"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  send(e);
                }
              }}
            />
            <button type="submit" disabled={loading || !input.trim()}>
              Send →
            </button>
          </form>
        </div>
      </div>
    </>
  );
}
