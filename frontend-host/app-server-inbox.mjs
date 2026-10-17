// The official UI requests bootstrap data before it announces readiness. Replies
// must flow immediately; unsolicited events wait until its listeners are ready.
export function createAppServerInbox({ isReady, dispatch }) {
  const pending = [];
  return {
    receive(message) {
      if (message.method && !isReady()) pending.push(message);
      else dispatch(message);
    },
    flush() {
      if (isReady()) for (const message of pending.splice(0)) dispatch(message);
    },
  };
}
