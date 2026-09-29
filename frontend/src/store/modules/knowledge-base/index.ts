const PART_UPLOAD_CONCURRENCY = 3;

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());
  const directUploadControllers = new Map<string, Set<AbortController>>();

  async function initializeMultipart(task: Api.KnowledgeBase.UploadTask) {
    if (task.fileUploadId && task.uploadProtocol === 'S3_MULTIPART') return;
    if (!task.file) throw new Error('请选择需要上传的本地文件');

    const { error, data } = await request<Api.KnowledgeBase.MultipartInit>({
      url: '/upload/multipart/init',
      method: 'POST',
      data: {
        fileName: task.fileName,
        totalSize: task.totalSize,
        fileMd5: task.fileMd5,
        contentType: task.file.type || 'application/octet-stream',
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false
      }
    });
    if (error || !data) throw new Error('创建Multipart上传任务失败');

    task.fileUploadId = data.fileUploadId;
    task.uploadProtocol = 'S3_MULTIPART';
    task.partSize = data.partSize;
    task.totalParts = data.totalParts;
    if (data.status === 'COMPLETED') {
      task.progress = 100;
      task.status = UploadStatus.Completed;
    }
  }

  async function refreshMultipartStatus(task: Api.KnowledgeBase.UploadTask) {
    const { error, data } = await request<Api.KnowledgeBase.MultipartStatus>({
      url: `/upload/multipart/${task.fileUploadId}/status`
    });
    if (error || !data) throw new Error('查询Multipart上传状态失败');

    task.partSize = data.partSize;
    task.totalParts = data.totalParts;
    task.uploadedChunks = data.uploadedParts.map(part => part.partNumber);
    task.progress = Number.parseFloat(data.progress.toFixed(2));
    if (data.status === 'COMPLETED') task.status = UploadStatus.Completed;
    if (data.status === 'ABORTED') throw new Error('上传任务已取消');
    return data;
  }

  async function uploadPart(task: Api.KnowledgeBase.UploadTask, partNumber: number) {
    if (!task.file || !task.fileUploadId || !task.partSize) throw new Error('上传任务信息不完整');
    const { error, data } = await request<Api.KnowledgeBase.MultipartPresign>({
      url: `/upload/multipart/${task.fileUploadId}/parts/${partNumber}/presign`,
      method: 'POST'
    });
    if (error || !data) throw new Error(`获取Part ${partNumber}上传地址失败`);

    const start = (partNumber - 1) * task.partSize;
    const body = task.file.slice(start, Math.min(start + task.partSize, task.totalSize));
    const controller = new AbortController();
    const controllers = directUploadControllers.get(task.fileMd5) ?? new Set<AbortController>();
    controllers.add(controller);
    directUploadControllers.set(task.fileMd5, controllers);
    try {
      const response = await fetch(data.url, { method: 'PUT', body, signal: controller.signal });
      if (!response.ok) throw new Error(`Part ${partNumber}上传失败: HTTP ${response.status}`);

      if (!task.uploadedChunks.includes(partNumber)) task.uploadedChunks.push(partNumber);
      const uploadedBytes = task.uploadedChunks.reduce((total, number) => {
        const offset = (number - 1) * task.partSize!;
        return total + Math.min(task.partSize!, task.totalSize - offset);
      }, 0);
      task.progress = Number.parseFloat(Math.min(100, (uploadedBytes * 100) / task.totalSize).toFixed(2));
    } finally {
      controllers.delete(controller);
    }
  }

  async function completeMultipart(task: Api.KnowledgeBase.UploadTask) {
    const { error } = await request({
      url: `/upload/multipart/${task.fileUploadId}/complete`,
      method: 'POST',
      timeout: 10 * 60 * 1000
    });
    if (error) throw new Error('完成Multipart上传失败');
    task.progress = 100;
    task.status = UploadStatus.Completed;
  }

  async function uploadFile(task: Api.KnowledgeBase.UploadTask) {
    await initializeMultipart(task);
    if (task.status === UploadStatus.Completed) return;
    const remoteStatus = await refreshMultipartStatus(task);
    if (remoteStatus.status === 'COMPLETED') return;

    const uploaded = new Set(task.uploadedChunks);
    const missingParts = Array.from({ length: task.totalParts! }, (_, index) => index + 1).filter(
      partNumber => !uploaded.has(partNumber)
    );
    const worker = async () => {
      while (missingParts.length > 0) {
        const partNumber = missingParts.shift();
        if (partNumber !== undefined) await uploadPart(task, partNumber);
      }
    };
    await Promise.all(
      Array.from({ length: Math.min(PART_UPLOAD_CONCURRENCY, missingParts.length) }, () => worker())
    );
    await completeMultipart(task);
  }

  async function enqueueUpload(form: Api.KnowledgeBase.Form) {
    const file = form.fileList![0].file!;
    const md5 = await calculateMD5(file);
    const existingTask = tasks.value.find(task => task.fileMd5 === md5);
    if (existingTask) {
      if (existingTask.status === UploadStatus.Completed) {
        window.$message?.error('文件已存在');
        return;
      }
      if (existingTask.status === UploadStatus.Pending || existingTask.status === UploadStatus.Uploading) {
        window.$message?.error('文件正在上传中');
        return;
      }
      existingTask.file = file;
      existingTask.status = UploadStatus.Pending;
      startUpload();
      return;
    }

    tasks.value.push({
      file,
      fileMd5: md5,
      fileName: file.name,
      chunkIndex: 0,
      totalSize: file.size,
      isPublic: form.isPublic,
      public: form.isPublic,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag,
      orgTagName: form.orgTagName ?? null
    });
    startUpload();
  }

  function startUpload() {
    if (activeUploads.value.size >= 3) return;
    const task = tasks.value.find(
      item => item.status === UploadStatus.Pending && !activeUploads.value.has(item.fileMd5)
    );
    if (!task) return;

    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.fileMd5);
    uploadFile(task)
      .catch(error => {
        console.error('Multipart upload failed', error);
        task.status = UploadStatus.Break;
      })
      .finally(() => {
        activeUploads.value.delete(task.fileMd5);
        directUploadControllers.delete(task.fileMd5);
        startUpload();
      });
    startUpload();
  }

  function cancelLocalUpload(task: Api.KnowledgeBase.UploadTask) {
    directUploadControllers.get(task.fileMd5)?.forEach(controller => controller.abort());
    directUploadControllers.delete(task.fileMd5);
    activeUploads.value.delete(task.fileMd5);
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload,
    cancelLocalUpload
  };
});
