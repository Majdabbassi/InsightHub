export type RelationshipStatus = 'SUGGESTED' | 'CONFIRMED' | 'REJECTED' | 'MANUAL';
export type RelationshipType = 'FOREIGN_KEY' | 'SIBLING';

export interface DatasetRelationship {
  id: number;
  projectId: number;
  datasetAId: number;
  datasetAName: string;
  datasetBId: number;
  datasetBName: string;
  sharedColumnA: string;
  sharedColumnB: string;
  matchPercentage: number | null;
  status: RelationshipStatus;
  relationshipType?: RelationshipType;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRelationshipRequest {
  datasetAId: number;
  datasetBId: number;
  sharedColumnA: string;
  sharedColumnB: string;
}

export interface RelationshipScanResponse {
  scannedPairs: number;
  createdRelationships: number;
}
