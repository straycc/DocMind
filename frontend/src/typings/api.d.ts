/**
 * Namespace Api
 *
 * All backend api type
 */
declare namespace Api {
  namespace Common {
    /** common params of paginating */
    interface PaginatingCommonParams {
      /** current page number */
      page?: number;
      number: number;
      /** page size */
      size?: number;
      /** total count */
      totalElements: number;
    }

    /** common params of paginating query list data */
    interface PaginatingQueryRecord<T = any> extends PaginatingCommonParams {
      data: T[];
      content: T[];
    }

    /** common search params of table */
    type CommonSearchParams = Pick<Common.PaginatingCommonParams, 'page' | 'size'>;
  }

  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      id: number;
      username: string;
      role: 'USER' | 'ADMIN';
      orgTags: string[];
      primaryOrg: string;
    }
  }

  /**
   * namespace Route
   *
   * backend api module: "route"
   */
  namespace Route {
    type ElegantConstRoute = import('@elegant-router/types').ElegantConstRoute;

    interface MenuRoute extends ElegantConstRoute {
      id: string;
    }

    interface UserRoute {
      routes: MenuRoute[];
      home: import('@elegant-router/types').LastLevelRouteKey;
    }
  }

  namespace OrgTag {
    interface Item {
      tagId: string;
      name: string;
      description: string;
      parentTag: string | null;
      children?: Item[];
    }

    type List = Common.PaginatingQueryRecord<Item>;

    type Details = Pick<Item, 'tagId' | 'name' | 'description'>;
    type Mine = {
      orgTags: string[];
      primaryOrg: string;
      orgTagDetails: Details[];
    };
  }

  namespace User {
    type Role = 'ADMIN' | 'USER';
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        role: Role;
      }
    >;

    type Item = {
      userId: string;
      username: string;
      email: string;
      role: Role;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createTime: string;
      lastLoginTime: string;
    };

    type List = Common.PaginatingQueryRecord<Item>;
  }

  namespace KnowledgeBase {
    interface SearchParams {
      userId: string;
      query: string;
      topK: number;
    }

    interface SearchResult {
      fileUploadId?: number;
      fileMd5: string;
      chunkId: number;
      textContent: string;
      score: number;
      fileName: string;
      titlePath?: string;
      pageStart?: number;
      pageEnd?: number;
      sourceLabel?: string;
    }

    interface UploadState {
      tasks: UploadTask[];
      activeUploads: Set<string>; // 当前正在上传的任务ID
    }

    interface Form {
      orgTag: string | null;
      orgTagName: string | null;
      isPublic: boolean;
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
      file?: File;
      chunk?: Blob | null;
      fileUploadId?: number;
      fileMd5: string;
      chunkIndex: number;
      totalSize: number;
      fileName: string;
      orgTag: string | null;
      orgTagName?: string | null;
      public: boolean;
      isPublic: boolean;
      uploadedChunks: number[];
      progress: number;
      status: UploadStatus;
      createdAt?: string;
      mergedAt?: string;
      requestIds?: string[]; // 请求ID，用于取消上传
      uploadProtocol?: 'S3_MULTIPART' | 'LEGACY_CHUNK';
      partSize?: number;
      totalParts?: number;
    }
    type List = Common.PaginatingQueryRecord<UploadTask>;

    type Merge = Pick<UploadTask, 'fileMd5' | 'fileName'>;

    interface Progress {
      uploaded: number[];
      progress: number;
      totalChunks: number;
    }

    interface Result {
      objectUrl: string;
      fileSize: number;
    }

    interface MultipartInit {
      fileUploadId: number;
      uploadId: string | null;
      objectKey: string;
      partSize: number;
      totalParts: number;
      status: 'UPLOADING' | 'COMPLETED';
    }

    interface MultipartPart {
      partNumber: number;
      etag: string;
      size: number;
    }

    interface MultipartStatus {
      fileUploadId: number;
      status: 'UPLOADING' | 'COMPLETED' | 'ABORTED';
      partSize: number;
      totalParts: number;
      uploadedParts: MultipartPart[];
      progress: number;
    }

    interface MultipartPresign {
      partNumber: number;
      url: string;
      expiresInSeconds: number;
    }
  }

  namespace Chat {
    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
      type?: 'start' | 'chunk' | 'completion' | 'stop' | 'error';
      conversationId?: string;
      turnId?: string;
    }

    interface Source {
      sourceId: number;
      fileUploadId: number | null;
      chunkOrdinal: number | null;
      fileName: string | null;
      titlePath: string | null;
      pageStart: number | null;
      pageEnd: number | null;
      sourceLabel: string;
      excerpt: string;
    }

    interface CitationValidation {
      citedSourceIds: number[];
      invalidCitationIds: number[];
      hasCitation: boolean;
      allCitationIdsValid: boolean;
    }

      interface Conversation {
        conversationId: string;
        title: string;
        createdAt: string;
        updatedAt: string;
      }

    interface Message {
      role: 'user' | 'assistant';
      content: string;
      status?: 'pending' | 'loading' | 'finished' | 'error';
      timestamp?: string;
      sources?: Source[];
      citationValidation?: CitationValidation;
      turnId?: string;
      queryRewritten?: boolean;
    }

    interface Token {
      cmdToken: string;
    }
  }

  namespace Document {
    interface DownloadResponse {
      fileName: string;
      downloadUrl: string;
      fileSize: number;
    }
  }
}
