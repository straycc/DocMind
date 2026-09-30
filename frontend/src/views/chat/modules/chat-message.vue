<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// citationValidation 是可由回答正文和结构化 sources 推导出的临时结果。
// WebSocket 实时消息会直接携带；页面刷新加载历史时则在前端重建。
const citationValidation = computed<Api.Chat.CitationValidation>(() => {
  if (props.msg.citationValidation) return props.msg.citationValidation;

  const citedSourceIds = Array.from(props.msg.content.matchAll(/【来源#\s*(\d+)】/g), match => Number(match[1])).filter(
    (sourceId, index, values) => values.indexOf(sourceId) === index
  );
  const availableSourceIds = new Set((props.msg.sources || []).map(source => source.sourceId));
  const invalidCitationIds = citedSourceIds.filter(sourceId => !availableSourceIds.has(sourceId));

  return {
    citedSourceIds,
    invalidCitationIds,
    hasCitation: citedSourceIds.length > 0,
    allCitationIdsValid: invalidCitationIds.length === 0
  };
});

const citedSources = computed(() => {
  const citedIds = citationValidation.value.citedSourceIds;
  return (props.msg.sources || []).filter(source => citedIds.includes(source.sourceId));
});

// 将经过后端编号校验的引用标记渲染为可点击链接。
function processSourceLinks(text: string): string {
  const sourcePattern = /【来源#(\d+)】/g;

  return text.replace(sourcePattern, (match, sourceId) => {
    const source = (props.msg.sources || []).find(item => item.sourceId === Number(sourceId));
    if (!source || !source.fileName) return match;
    return `<span class="source-file-link" data-source-id="${source.sourceId}">${match}</span>`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查是否是经过来源映射的文件链接。
  if (target.classList.contains('source-file-link')) {
    const sourceId = Number(target.getAttribute('data-source-id'));
    const source = (props.msg.sources || []).find(item => item.sourceId === sourceId);
    if (source?.fileName) {
      handleSourceFileClick(source.fileName);
    }
  }
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);

  try {
    window.$message?.loading(`正在获取文件下载链接: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });

    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName: decodedFileName,
        token: authStore.token
      },
      baseURL: '/proxy-api'
    });

    window.$message?.destroyAll();

    if (error) {
      window.$message?.error(`文件下载失败: ${error.response?.data?.message || '未知错误'}`);
      return;
    }

    if (data?.downloadUrl) {
      // 在新窗口打开下载链接
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`文件下载链接已打开: ${decodedFileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch {
    window.$message?.destroyAll();
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="message-row" :class="msg.role === 'user' ? 'message-row--user' : 'message-row--assistant'">
    <div class="message-column">
      <div class="message-meta" :class="{ 'justify-end': msg.role === 'user' }">
        <NText class="text-3.5 font-semibold">
          {{ msg.role === 'user' ? authStore.userInfo.username : 'DocMind' }}
        </NText>
        <NText depth="3" class="text-3">{{ formatDate(msg.timestamp) }}</NText>
      </div>

      <div class="message-bubble" :class="`message-bubble--${msg.role}`">
        <div v-if="msg.status === 'pending'" class="py-1">
          <icon-eos-icons:three-dots-loading class="text-7 text-primary" />
        </div>
        <NText v-else-if="msg.status === 'error'" type="error">服务器繁忙，请稍后再试</NText>
        <div v-else-if="msg.role === 'assistant'" class="assistant-content" @click="handleContentClick">
          <VueMarkdownIt :content="content" />
          <NText v-if="!citationValidation.allCitationIdsValid" type="error" class="mt-2 block text-3">
            回答中存在未匹配的引用编号：{{ citationValidation.invalidCitationIds.join('、') }}
          </NText>
          <div v-if="citedSources.length" class="mt-4 border-t border-#eef0f5 pt-3">
            <NText depth="3" class="mb-2 block text-3">引用来源</NText>
            <div class="flex flex-wrap items-center gap-2">
              <NTag
                v-for="source in citedSources"
                :key="source.sourceId"
                size="small"
                type="info"
                :bordered="false"
                :title="source.excerpt"
                class="cursor-pointer"
                @click.stop="source.fileName && handleSourceFileClick(source.fileName)"
              >
                【来源#{{ source.sourceId }}】{{ source.sourceLabel }}
              </NTag>
            </div>
          </div>
        </div>
        <NText v-else class="whitespace-pre-wrap text-4 leading-7">{{ content }}</NText>
      </div>

      <div class="message-actions" :class="{ 'justify-end': msg.role === 'user' }">
        <NButton quaternary size="tiny" title="复制" @click="handleCopy(msg.content)">
          <template #icon><icon-mynaui:copy /></template>
        </NButton>
      </div>
    </div>
    <NAvatar v-if="msg.role === 'user'" round class="message-avatar message-avatar--user">
      <SvgIcon icon="ph:user" class="text-5 text-white" />
    </NAvatar>
  </div>
</template>

<style scoped lang="scss">
.message-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 24px;
}

.message-row--user {
  justify-content: flex-end;
}

.message-column {
  min-width: 0;
  width: 100%;
}

.message-row--user .message-column {
  width: auto;
  max-width: 82%;
}

.message-meta,
.message-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.message-meta {
  margin-bottom: 7px;
}

.message-actions {
  min-height: 28px;
  margin-top: 3px;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.message-row:hover .message-actions {
  opacity: 1;
}

.message-avatar {
  flex: 0 0 auto;
  box-shadow: 0 4px 12px rgb(35 65 100 / 8%);
}

.message-avatar--user {
  background: #334155;
}

.message-bubble {
  border-radius: 16px;
  line-height: 1.75;
}

.message-bubble--assistant {
  border: 1px solid #e9eaf0;
  border-top-left-radius: 5px;
  background: #fff;
  padding: 16px 18px;
  box-shadow: 0 6px 24px rgb(35 65 100 / 4%);
}

.message-bubble--user {
  border-top-right-radius: 5px;
  background: rgb(var(--primary-color) / 10%);
  padding: 12px 16px;
  color: #253c58;
}

.message-bubble--user :deep(.n-text) {
  color: #253c58;
}

.assistant-content :deep(p:first-child) {
  margin-top: 0;
}

.assistant-content :deep(p:last-child) {
  margin-bottom: 0;
}

:deep(.source-file-link) {
  color: rgb(var(--primary-color));
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: rgb(var(--primary-600-color));
    text-decoration: none;
  }

  &:active {
    color: rgb(var(--primary-700-color));
  }
}

@media (max-width: 640px) {
  .message-row--user .message-column {
    max-width: calc(100% - 48px);
  }
}
</style>
