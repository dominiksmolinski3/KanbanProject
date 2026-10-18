export type Id = number;

export type BoardRole = 'MEMBER' | 'VIEWER';
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'REVOKED';
export type TaskActivityType =
  | 'CREATED' | 'MOVED' | 'ASSIGNED' | 'UNASSIGNED' | 'COMPLETED' | 'REOPENED' | 'DELETED' | 'COMMENTED';
export type MessageType = 'CHAT' | 'JOIN' | 'LEAVE' | 'PRIVATE' | 'NOTIFICATION';

export interface Task {
  id: Id;
  version: number;
  title: string;
  position: number;
  columnId: Id;
  rowId: Id | null;
  userIds: Id[];
  labels: string[];
  completed: boolean;
  description: string | null;
  parentTaskId: Id | null;
  childTaskIds: Id[];
  deadline: string | null;
  expired: boolean;
  dailyFocus: boolean;
  openSubtasks: number;
  subtaskCount: number;
}

export interface TaskPatch {
  title?: string;
  description?: string | null;
  position?: number;
  deadline?: string | null;
  labels?: string[];
  column?: { id: Id };
  row?: { id: Id } | null;
  version?: number;
}

export interface Column {
  id: Id;
  name: string;
  position: number;
  wipLimit: number;
  taskDTO: Task[];
}

export type Row = Column;

export type ColumnSummary = Omit<Column, 'taskDTO'>;
export type RowSummary = ColumnSummary;

export interface SubTask {
  id: Id;
  title: string;
  description: string | null;
  completed: boolean;
  position: number;
  taskId: Id;
}

export interface User {
  id: Id;
  email: string;
  name: string;
  wipLimit: number;
  locale: string | null;
}

export interface WipStatus {
  userId: Id;
  wipLimit: number;
  assignedCount: number;
  withinLimit: boolean;
}

export interface BoardMember extends User {
  role: BoardRole;
}

export interface Board {
  id: Id;
  name: string;
  ownerId: Id;
  owned: boolean;
  members: BoardMember[];
  role: BoardRole;
}

export interface BoardInvitation {
  id: Id;
  boardId: Id;
  boardName: string;
  email: string;
  invitedByName: string;
  status: InvitationStatus;
  role: BoardRole;
  createdAt: string;
}

export interface TaskComment {
  id: number;
  taskId: Id;
  body: string;
  authorId: Id;
  authorName: string;
  createdAt: string;
  editedAt: string | null;
}

export interface TaskAttachment {
  id: number;
  taskId: Id;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedById: Id;
  uploadedByName: string;
  uploadedAt: string;
}

export interface TaskColumnHistoryEntry {
  id: Id;
  taskId: Id;
  taskTitle: string;
  columnId: Id;
  columnName: string;
  changedAt: string;
}

export interface ColumnTimeSpent {
  columnId: Id;
  columnName: string;
  totalTimeMs: number;
}

export interface TaskActivity {
  id: Id;
  taskId: Id;
  taskTitle: string;
  actorId: Id;
  actorName: string;
  type: TaskActivityType;
  detail: string | null;
  occurredAt: string;
}

export interface ChatMessage {
  id: Id;
  type: MessageType;
  content: string;
  sender: string;
  boardId: Id | null;
  recipientId: string | null;
  timestamp: string;
}

export interface ActiveDevice {
  id: number;
  ipAddress: string | null;
  userAgent: string | null;
  signedInAt: string;
  lastSeenAt: string;
  expiresAt: string;
  redacted: boolean;
}

export interface FlowDefinition {
  boardId: Id;
  startColumnId: Id | null;
  doneColumnId: Id | null;
}

export interface FlowMetrics {
  boardId: Id;
  from: string;
  to: string;
  startColumnId: Id | null;
  doneColumnId: Id | null;
  definedStartColumnId: Id | null;
  definedDoneColumnId: Id | null;
  columns: { id: Id; name: string; position: number }[];
  cumulativeFlow: { date: string; counts: number[] }[];
  cycleTime: { count: number; averageHours: number | null; medianHours: number | null; p85Hours: number | null };
  samples: { taskId: Id; title: string; doneAt: string; hours: number }[];
  throughput: { date: string; count: number }[];
}

export interface LoginResponse {
  token: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
  sessionId: number | null;
}

export interface PageRequest {
  page?: number;
  size?: number;
}

interface PageInfo {
  page: number;
  size: number;
  totalPages: number;
}

export interface TaskSearchResults extends PageInfo {
  tasks: Task[];
  totalTasks: number;
}

export interface TaskActivityResults extends PageInfo {
  activities: TaskActivity[];
  totalEntries: number;
}

export interface TaskCommentResults extends PageInfo {
  comments: TaskComment[];
  totalEntries: number;
}

export interface ChatMessageResults extends PageInfo {
  messages: ChatMessage[];
  totalEntries: number;
}
