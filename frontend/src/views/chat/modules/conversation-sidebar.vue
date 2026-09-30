<script setup lang="ts">
defineOptions({ name: 'ConversationSidebar' });

const chatStore = useChatStore();
const router = useRouter();
const { conversations, conversationId, list } = storeToRefs(chatStore);

const isAnswering = computed(() => {
  const latest = list.value[list.value.length - 1];
  return latest?.role === 'assistant' && ['pending', 'loading'].includes(latest.status || '');
});

async function createConversation() {
  if (isAnswering.value) {
    window.$message?.warning('请等待当前回答结束后再新建会话');
    return;
  }
  await chatStore.createConversation();
  await router.push('/chat');
}

async function selectConversation(id: string) {
  if (isAnswering.value || id === conversationId.value) return;
  await chatStore.selectConversation(id);
  await router.push('/chat');
}

async function deleteConversation(id: string) {
  if (isAnswering.value) return;
  await chatStore.deleteConversation(id);
}

onMounted(() => chatStore.loadConversations(false));
</script>

<template>
  <aside class="conversation-sidebar">
    <NButton block quaternary class="new-conversation" @click="createConversation">
      <template #icon><icon-solar:add-circle-linear /></template>
      新对话
    </NButton>

    <NScrollbar class="conversation-scroll">
      <div v-if="!conversations.length" class="conversation-empty text-3 text-gray-400">暂无对话</div>
      <div
        v-for="conversation in conversations"
        :key="conversation.conversationId"
        class="conversation-item"
        :class="{ 'conversation-item--active': conversation.conversationId === conversationId }"
        role="button"
        tabindex="0"
        @click="selectConversation(conversation.conversationId)"
        @keydown.enter="selectConversation(conversation.conversationId)"
      >
        <span class="min-w-0 flex-1 truncate text-left">{{ conversation.title }}</span>
        <NPopconfirm
          positive-text="删除"
          negative-text="取消"
          @positive-click="deleteConversation(conversation.conversationId)"
        >
          <template #trigger>
            <span class="delete-button" role="button" title="删除会话" @click.stop>
              <icon-solar:trash-bin-trash-linear />
            </span>
          </template>
          确定删除这个会话吗？
        </NPopconfirm>
      </div>
    </NScrollbar>
  </aside>
</template>

<style scoped lang="scss">
.conversation-sidebar {
  display: flex;
  flex-direction: column;
  padding: 4px 0 8px;
}

.new-conversation {
  justify-content: flex-start;
  padding-left: 12px;
  height: 34px;
  color: #737b8b;
  font-size: 12px;
}
.conversation-scroll {
  max-height: 38vh;
}

.conversation-empty {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
  text-align: center;
}

.conversation-item {
  display: flex;
  width: calc(100% - 12px);
  margin-left: 12px;
  align-items: center;
  gap: 4px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #4b5563;
  cursor: pointer;
  padding: 8px 9px;
  font-size: 12px;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.conversation-item:hover,
.conversation-item--active {
  background: rgb(var(--primary-color) / 10%);
  color: rgb(var(--primary-color));
}

.delete-button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  opacity: 0;
  padding: 3px;
}

.conversation-item:hover .delete-button,
.conversation-item--active .delete-button {
  opacity: 1;
}
</style>
