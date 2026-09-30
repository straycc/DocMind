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
  console.log('点击了来源文件:', decodedFileName);

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
  } catch (err) {
    window.$message?.destroyAll();
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar class="bg-primary">
        <SystemLogo class="text-6 text-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">派聪明</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic">服务器繁忙，请稍后再试</NText>
    <div v-else-if="msg.role === 'assistant'" class="mt-2 pl-12" @click="handleContentClick">
      <VueMarkdownIt :content="content" />
      <NText v-if="!citationValidation.allCitationIdsValid" type="error" class="mt-2 block text-3">
        回答中存在未匹配的引用编号：{{ citationValidation.invalidCitationIds.join('、') }}
      </NText>
      <div v-if="citedSources.length" class="mt-3 flex flex-wrap items-center gap-2 text-3">
        <NText depth="3">引用来源：</NText>
        <NTag
          v-for="source in citedSources"
          :key="source.sourceId"
          size="small"
          type="info"
          :title="source.excerpt"
          class="cursor-pointer"
          @click.stop="source.fileName && handleSourceFileClick(source.fileName)"
        >
          【来源#{{ source.sourceId }}】{{ source.sourceLabel }}
        </NTag>
      </div>
    </div>
    <NText v-else-if="msg.role === 'user'" class="ml-12 mt-2 text-4">{{ content }}</NText>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }
}
</style>
