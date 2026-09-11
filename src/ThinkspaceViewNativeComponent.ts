import { codegenNativeComponent, type ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Float,
} from 'react-native/Libraries/Types/CodegenTypes';

export type StrokeEvent = Readonly<{
  strokeJson: string;
}>;

export type StrokeIdEvent = Readonly<{
  id: string;
}>;

export type ExcerptMoveEvent = Readonly<{
  id: string;
  x: Float;
  y: Float;
  clusterId?: string;
  stackCount?: Float;
}>;

export type ExcerptPressEvent = Readonly<{
  id: string;
}>;

export type CardDeleteEvent = Readonly<{
  id: string;
}>;

export type CardColorChangeEvent = Readonly<{
  id: string;
  color: string;
}>;

export type CardCommentEvent = Readonly<{
  id: string;
  comment: string;
}>;

export type CardHoldEvent = Readonly<{
  id: string;
}>;

export type TransformChangeEvent = Readonly<{
  panX: Float;
  panY: Float;
  scale: Float;
}>;

export type SplitRatioChangeEvent = Readonly<{
  ratio: Float;
}>;

export type ExtractExcerptEvent = Readonly<{
  text: string;
  pageNumber: Float;
  color: string;
  isTable: boolean;
  isImage: boolean;
  imageUrl?: string;
  id?: string;
  x?: Float;
  y?: Float;
}>;

export type ToggleSqueezeEvent = Readonly<{
  isSqueezed: boolean;
}>;

export interface NativeProps extends ViewProps {
  documentJson?: string;
  annotationsJson?: string;
  isSqueezed?: boolean;
  splitRatio?: Float;
  activeTool?: string;
  selectedColor?: string;
  pattern?: string;
  strokesJson?: string;
  excerptsJson?: string;
  inkLinksJson?: string;
  panX?: Float;
  panY?: Float;
  scale?: Float;
  onAddStroke?: DirectEventHandler<StrokeEvent>;
  onEraseStroke?: DirectEventHandler<StrokeIdEvent>;
  onExcerptMoveEnd?: DirectEventHandler<ExcerptMoveEvent>;
  onExcerptPress?: DirectEventHandler<ExcerptPressEvent>;
  onCardDelete?: DirectEventHandler<CardDeleteEvent>;
  onChangeCardColor?: DirectEventHandler<CardColorChangeEvent>;
  onUpdateCardComment?: DirectEventHandler<CardCommentEvent>;
  onHoldCard?: DirectEventHandler<CardHoldEvent>;
  onTransformChange?: DirectEventHandler<TransformChangeEvent>;
  onSplitRatioChange?: DirectEventHandler<SplitRatioChangeEvent>;
  onExtractExcerpt?: DirectEventHandler<ExtractExcerptEvent>;
  onToggleSqueeze?: DirectEventHandler<ToggleSqueezeEvent>;
}

export default codegenNativeComponent<NativeProps>('ThinkspaceView');
