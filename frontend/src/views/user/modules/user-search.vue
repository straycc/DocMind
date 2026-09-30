<script setup lang="ts">
import { userRoleOptions } from '@/constants/common';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
}>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });

watchEffect(() => {
  search();
});
async function search() {
  emit('search');
}
</script>

<template>
  <NCard :bordered="false" size="small" class="rd-full px-6">
    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" inline>
      <NFormItem label="关键词" path="keyword">
        <NInput v-model:value="model.keyword" placeholder="请输入关键词" clearable />
      </NFormItem>
      <NFormItem label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" clearable class="w-200px!" />
      </NFormItem>
      <NFormItem label="用户角色" path="role">
        <NSelect
          v-model:value="model.role"
          placeholder="请选择用户角色"
          :options="userRoleOptions"
          clearable
          class="w-200px!"
        />
      </NFormItem>
    </NForm>
  </NCard>
</template>

<style scoped></style>
