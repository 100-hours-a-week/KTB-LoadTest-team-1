const getTime = (message) => new Date(message?.timestamp || 0).getTime();

const isSortedAscending = (list) => {
  for (let i = 1; i < list.length; i++) {
    if (getTime(list[i]) < getTime(list[i - 1])) {
      return false;
    }
  }
  return true;
};

// currentMessages와 newMessages는 각각 이미 타임스탬프 오름차순이라는 전제로,
// 병합 정렬(O(n))만 수행한다 — 매 호출마다 전체 배열을 재정렬(O(n log n))하지 않기 위함.
const mergeSortedByTimestamp = (a, b) => {
  const merged = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    merged.push(getTime(a[i]) <= getTime(b[j]) ? a[i++] : b[j++]);
  }
  while (i < a.length) merged.push(a[i++]);
  while (j < b.length) merged.push(b[j++]);
  return merged;
};

export const deriveUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  const processedSnapshot = new Set(processedMessageIds);
  const nextProcessedMessageIds = new Set(processedMessageIds);
  const newMessages = incomingMessages.filter((message) => {
    if (!message._id) {
      return false;
    }

    if (processedSnapshot.has(message._id)) {
      return false;
    }

    processedSnapshot.add(message._id);
    nextProcessedMessageIds.add(message._id);
    return true;
  });

  if (newMessages.length === 0) {
    return {
      messages: currentMessages,
      processedMessageIds: nextProcessedMessageIds,
    };
  }

  // incoming 배치(보통 최대 30건)가 정렬돼 있지 않을 때만 그 배치만 정렬한다.
  // currentMessages는 이 함수를 통해서만 갱신되므로 항상 정렬 상태가 유지된다.
  const sortedNew = isSortedAscending(newMessages)
    ? newMessages
    : [...newMessages].sort((a, b) => getTime(a) - getTime(b));

  const messages = currentMessages.length === 0
    ? sortedNew
    : mergeSortedByTimestamp(currentMessages, sortedNew);

  return {
    messages,
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const mergeUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  return deriveUniqueSortedMessages(
    currentMessages,
    incomingMessages,
    processedMessageIds
  ).messages;
};
