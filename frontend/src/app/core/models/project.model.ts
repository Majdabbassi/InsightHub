export interface Project {
  id: number;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  ownerEmail: string;
}

export interface CreateProjectRequest {
  name: string;
  description?: string | null;
}

export type UpdateProjectRequest = CreateProjectRequest;
