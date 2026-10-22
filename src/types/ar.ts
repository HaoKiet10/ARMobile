export interface SpatialVector {
  x: number;
  y: number;
  z: number;
}

export interface AssetTransform {
  position: SpatialVector;
  rotation: SpatialVector;
  scale: SpatialVector;
}

export interface ARAsset {
  id: string;
  filename: string;
  fileType: string; // MIME type, vd: "model/gltf-binary"
  url: string;
  transform: AssetTransform | null;
}

// Khớp đúng response của GET /public/projects/:id (scan.controller.ts)
export interface PublicProject {
  id: string;
  name: string;
  description: string | null;
  triggerImageUrl: string | null;
  assets: ARAsset[];
}
// Khớp response của GET /public/trigger-db (scan.controller.ts -> TriggerDbController)
export interface TriggerDb {
  version: string;
  /** null khi chưa có project nào publish */
  url: string | null;
  sha256: string | null;
  size: number;
  /** Project có trong database (name trong database = project id) */
  triggers: { id: string; name: string }[];
}