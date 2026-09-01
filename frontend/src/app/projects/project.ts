export const PROJECT_STATUSES = [
  'PLANNING',
  'IN_PROGRESS',
  'ON_HOLD',
  'COMPLETED',
  'CANCELLED',
] as const;

export type ProjectStatus = (typeof PROJECT_STATUSES)[number];

export interface Project {
  id: string;
  name: string;
  description: string | null;
  status: ProjectStatus;
  customerId: string | null;
  customerName: string | null;
  ownerId: string | null;
  ownerName: string | null;
  startDate: string | null;
  dueDate: string | null;
  overdue: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectRequest {
  name: string;
  description?: string | null;
  status?: ProjectStatus;
  customerId?: string | null;
  ownerId?: string | null;
  startDate?: string | null;
  dueDate?: string | null;
}
