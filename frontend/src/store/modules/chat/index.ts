import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const activeTurnId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);
  const conversations = ref<Api.Chat.Conversation[]>([]);
  const conversationLoading = ref(false);

  const store = useAuthStore();

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  async function loadConversations(selectFirst = true) {
    conversationLoading.value = true;
    const { error, data } = await request<Api.Chat.Conversation[]>({ url: 'users/conversations' });
    if (!error) {
      conversations.value = data;
      if (selectFirst && !conversationId.value && data.length) await selectConversation(data[0].conversationId);
    }
    conversationLoading.value = false;
  }

  async function createConversation() {
    const { error, data } = await request<Api.Chat.Conversation>({
      url: 'users/conversations',
      method: 'POST'
    });
    if (error) return '';
    conversations.value.unshift(data);
    conversationId.value = data.conversationId;
    list.value = [];
    activeTurnId.value = '';
    return data.conversationId;
  }

  async function selectConversation(id: string) {
    if (!id || id === conversationId.value) return;
    conversationLoading.value = true;
    const { error, data } = await request<Api.Chat.Message[]>({ url: `users/conversations/${id}/messages` });
    if (!error) {
      conversationId.value = id;
      list.value = data;
      activeTurnId.value = '';
    }
    conversationLoading.value = false;
  }

  async function deleteConversation(id: string) {
    const { error } = await request({ url: `users/conversations/${id}`, method: 'DELETE' });
    if (error) return;
    conversations.value = conversations.value.filter(item => item.conversationId !== id);
    if (conversationId.value === id) {
      conversationId.value = '';
      list.value = [];
      const next = conversations.value[0];
      if (next) await selectConversation(next.conversationId);
    }
  }

  return {
    input,
    conversationId,
    activeTurnId,
    list,
    conversations,
    conversationLoading,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    loadConversations,
    createConversation,
    selectConversation,
    deleteConversation
  };
});
