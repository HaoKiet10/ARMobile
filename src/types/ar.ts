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