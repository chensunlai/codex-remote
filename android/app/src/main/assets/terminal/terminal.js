(() => {
  'use strict';

  const terminal = new Terminal({
    cursorBlink: true,
    cursorStyle: 'bar',
    convertEol: false,
    fontFamily: 'monospace',
    fontSize: 14,
    scrollback: 5000,
    theme: {
      background: '#101214',
      foreground: '#e3e7ea',
      cursor: '#65b88c',
      selectionBackground: '#376b52',
    },
  });
  const fitAddon = new FitAddon.FitAddon();
  const container = document.getElementById('terminal');
  const status = document.getElementById('status');
  let lastCols = 0;
  let lastRows = 0;
  let initialReady = false;
  let resizeTimer = 0;

  terminal.loadAddon(fitAddon);
  terminal.open(container);

  function notifySize() {
    fitAddon.fit();
    const cols = terminal.cols;
    const rows = terminal.rows;
    if (cols < 2 || rows < 1) return;
    if (!initialReady) {
      initialReady = true;
      lastCols = cols;
      lastRows = rows;
      CodexTerminal.onReady(cols, rows);
    } else if (cols !== lastCols || rows !== lastRows) {
      lastCols = cols;
      lastRows = rows;
      CodexTerminal.onResize(cols, rows);
    }
  }

  terminal.onData((data) => CodexTerminal.onInput(data));
  new ResizeObserver(() => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(notifySize, 80);
  }).observe(container);

  window.remoteTerminal = {
    write(data) {
      terminal.write(data);
    },
    reset() {
      terminal.reset();
      terminal.clear();
    },
    setStatus(message, isError) {
      status.textContent = message || '';
      status.classList.toggle('error', Boolean(isError));
    },
    setFontScale(scale) {
      const normalized = Math.min(1.3, Math.max(0.85, Number(scale) || 1));
      terminal.options.fontSize = 14 * normalized;
      notifySize();
    },
    focus() {
      terminal.focus();
    },
  };

  requestAnimationFrame(notifySize);
})();
