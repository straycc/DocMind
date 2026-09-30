<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { SimpleScrollbar } from '@sa/materials';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useRouteStore } from '@/store/modules/route';
import { useRouterPush } from '@/hooks/common/router';
import ConversationSidebar from '@/views/chat/modules/conversation-sidebar.vue';
import { useMenu } from '../../../context';

defineOptions({
  name: 'VerticalMenu'
});

const route = useRoute();
const appStore = useAppStore();
const themeStore = useThemeStore();
const routeStore = useRouteStore();
const { routerPushByKeyWithMetaQuery } = useRouterPush();
const { selectedKey } = useMenu();

const inverted = computed(() => !themeStore.darkMode && themeStore.sider.inverted);

const expandedKeys = ref<string[]>([]);
const chatExpanded = ref(route.name === 'chat');
const otherMenus = computed(() => routeStore.menus.filter(menu => menu.key !== 'chat'));

function toggleChat() {
  if (appStore.siderCollapse) {
    appStore.toggleSiderCollapse();
    chatExpanded.value = true;
  } else if (route.name === 'chat') {
    chatExpanded.value = !chatExpanded.value;
  } else {
    chatExpanded.value = true;
  }
  routerPushByKeyWithMetaQuery('chat');
}

function updateExpandedKeys() {
  if (appStore.siderCollapse || !selectedKey.value) {
    expandedKeys.value = [];
    return;
  }
  expandedKeys.value = routeStore.getSelectedMenuKeyPath(selectedKey.value);
}

watch(
  () => route.name,
  () => {
    updateExpandedKeys();
    if (route.name === 'chat') chatExpanded.value = true;
  },
  { immediate: true }
);
</script>

<template>
  <Teleport :to="`#${GLOBAL_SIDER_MENU_ID}`">
    <SimpleScrollbar class="relative">
      <div class="chat-menu" :class="{ 'chat-menu--collapsed': appStore.siderCollapse }">
        <button class="chat-menu-heading" :class="{ 'is-active': route.name === 'chat' }" @click="toggleChat">
          <icon-solar:chat-round-call-line-duotone class="shrink-0 text-22px" />
          <template v-if="!appStore.siderCollapse">
            <span class="flex-1 text-left">聊天助手</span>
            <icon-material-symbols:keyboard-arrow-down-rounded
              class="text-18px transition-transform"
              :class="{ 'rotate-180': chatExpanded }"
            />
          </template>
        </button>
        <ConversationSidebar v-if="chatExpanded && !appStore.siderCollapse" />
      </div>
      <NMenu
        v-model:expanded-keys="expandedKeys"
        mode="vertical"
        :value="selectedKey"
        :collapsed="appStore.siderCollapse"
        :collapsed-width="themeStore.sider.collapsedWidth"
        :collapsed-icon-size="22"
        :options="otherMenus"
        :inverted="inverted"
        :indent="18"
        @update:value="routerPushByKeyWithMetaQuery"
      />
      <MenuToggler
        v-if="!appStore.isMobile"
        class="absolute bottom-0 w-full"
        :collapsed="appStore.siderCollapse"
        @click="appStore.toggleSiderCollapse"
      />
    </SimpleScrollbar>
  </Teleport>
</template>

<style scoped lang="scss">
.chat-menu {
  padding: 4px 8px 0;
}
.chat-menu-heading {
  display: flex;
  width: 100%;
  height: 42px;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.chat-menu-heading:hover {
  background: #f4f5f8;
}
.chat-menu-heading.is-active {
  background: rgb(var(--primary-color) / 9%);
  color: rgb(var(--primary-color));
}
.chat-menu--collapsed .chat-menu-heading {
  justify-content: center;
  padding: 0;
}
</style>
