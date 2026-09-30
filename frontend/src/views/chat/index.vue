<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

const chatStore = useChatStore();
onMounted(() => chatStore.loadConversations());
</script>

<template>
  <div class="h-full min-h-0 flex">
    <div class="min-w-0 flex flex-col flex-1">
      <ChatList />
      <div class="mx-auto max-w-1000px w-full px-5 pb-5">
        <InputBox />
      </div>
    </div>
  </div>
</template>

<style scoped></style>
