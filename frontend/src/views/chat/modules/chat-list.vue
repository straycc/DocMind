<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list } = storeToRefs(chatStore);

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <div v-if="!list.length" class="min-h-0 flex flex-1 items-center justify-center px-5 text-center">
    <NSpin :show="chatStore.conversationLoading">
      <div class="py-8">
        <h2 class="m-0 text-6 font-semibold">你好，我是 DocMind</h2>
        <p class="mt-3 text-3.5 text-gray-500">从知识库中检索可靠信息，并为回答标注来源</p>
      </div>
    </NSpin>
  </div>
  <Suspense v-else>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <NSpin :show="chatStore.conversationLoading" class="min-h-full">
        <VueMarkdownItProvider>
          <div v-if="list.length" class="mx-auto max-w-1000px w-full px-5 py-6">
            <ChatMessage v-for="(item, index) in list" :key="`${item.timestamp || 'message'}-${index}`" :msg="item" />
          </div>
        </VueMarkdownItProvider>
      </NSpin>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss"></style>
