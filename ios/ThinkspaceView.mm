#import "ThinkspaceView.h"

#import <React/RCTConversions.h>
#import <react/renderer/components/ThinkspaceViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/ThinkspaceViewSpec/EventEmitters.h>
#import <react/renderer/components/ThinkspaceViewSpec/Props.h>
#import <react/renderer/components/ThinkspaceViewSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

#import <PDFKit/PDFKit.h>
#import <PencilKit/PencilKit.h>

#if __has_include(<Thinkspace/Thinkspace-Swift.h>)
#import <Thinkspace/Thinkspace-Swift.h>
#elif __has_include("Thinkspace-Swift.h")
#import "Thinkspace-Swift.h"
#endif

using namespace facebook::react;

@interface ThinkspaceView () <ThinkspaceEventEmitterProtocol, RCTThinkspaceViewViewProtocol>
@end

@implementation ThinkspaceView {
    ThinkspaceContainerView * _containerView;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
    return concreteComponentDescriptorProvider<ThinkspaceViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const ThinkspaceViewProps>();
    _props = defaultProps;

    _containerView = [[ThinkspaceContainerView alloc] initWithFrame:frame];
    _containerView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [ThinkspaceBridgeEmitter shared].delegate = self;

    self.contentView = _containerView;
  }

  return self;
}

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps
{
    const auto &oldViewProps = *std::static_pointer_cast<const ThinkspaceViewProps>(_props);
    const auto &newViewProps = *std::static_pointer_cast<const ThinkspaceViewProps>(props);

    if (newViewProps.splitRatio != oldViewProps.splitRatio) {
        [_containerView updateSplitRatio:newViewProps.splitRatio];
    }

    if (newViewProps.isSqueezed != oldViewProps.isSqueezed) {
        [_containerView updateIsSqueezed:newViewProps.isSqueezed];
    }

    if (newViewProps.isImmersive != oldViewProps.isImmersive) {
        [_containerView updateIsImmersive:newViewProps.isImmersive];
    }

    if (newViewProps.activeTool != oldViewProps.activeTool) {
        [_containerView updateActiveTool:[NSString stringWithUTF8String:newViewProps.activeTool.c_str()]];
    }

    if (newViewProps.selectedColor != oldViewProps.selectedColor) {
        [_containerView updateSelectedColor:[NSString stringWithUTF8String:newViewProps.selectedColor.c_str()]];
    }

    if (newViewProps.pattern != oldViewProps.pattern) {
        [_containerView updatePattern:[NSString stringWithUTF8String:newViewProps.pattern.c_str()]];
    }

    if (newViewProps.strokesJson != oldViewProps.strokesJson) {
        [_containerView updateStrokesJson:[NSString stringWithUTF8String:newViewProps.strokesJson.c_str()]];
    }

    if (newViewProps.excerptsJson != oldViewProps.excerptsJson) {
        [_containerView updateExcerptsJson:[NSString stringWithUTF8String:newViewProps.excerptsJson.c_str()]];
    }

    if (newViewProps.inkLinksJson != oldViewProps.inkLinksJson) {
        [_containerView updateInkLinksJson:[NSString stringWithUTF8String:newViewProps.inkLinksJson.c_str()]];
    }

    if (newViewProps.documentJson != oldViewProps.documentJson) {
        [_containerView updateDocumentJson:[NSString stringWithUTF8String:newViewProps.documentJson.c_str()]];
    }

    if (newViewProps.notebookPagesJson != oldViewProps.notebookPagesJson) {
        [_containerView updateNotebookPagesJson:[NSString stringWithUTF8String:newViewProps.notebookPagesJson.c_str()]];
    }

    if (newViewProps.workspaceDocumentsJson != oldViewProps.workspaceDocumentsJson) {
        [_containerView updateWorkspaceDocumentsJson:[NSString stringWithUTF8String:newViewProps.workspaceDocumentsJson.c_str()]];
    }

    if (newViewProps.activeDocumentId != oldViewProps.activeDocumentId) {
        [_containerView updateActiveDocumentId:[NSString stringWithUTF8String:newViewProps.activeDocumentId.c_str()]];
    }

    if (newViewProps.penMode != oldViewProps.penMode) {
        _containerView.penMode = [NSString stringWithUTF8String:newViewProps.penMode.c_str()];
    }

    if (newViewProps.penColor != oldViewProps.penColor) {
        _containerView.penColor = [NSString stringWithUTF8String:newViewProps.penColor.c_str()];
    }

    if (newViewProps.penThickness != oldViewProps.penThickness) {
        _containerView.penThickness = newViewProps.penThickness;
    }

    [super updateProps:props oldProps:oldProps];
}

#pragma mark - Command Handling

- (void)handleCommand:(const NSString *)commandName args:(const NSArray *)args
{
    [_containerView.commandRouter dispatchCommandWithName:(NSString *)commandName args:(NSArray *)args];
}

#pragma mark - ThinkspaceEventEmitterProtocol Implementation

- (void)emitAddStrokeWithStrokeJson:(NSString *)strokeJson
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onAddStroke({[strokeJson UTF8String]});
    }
}

- (void)emitEraseStrokeWithId:(NSString *)id
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onEraseStroke({[id UTF8String]});
    }
}

- (void)emitExcerptMoveEndWithId:(NSString *)id
                               x:(CGFloat)x
                               y:(CGFloat)y
                       clusterId:(NSString *)clusterId
                      stackCount:(NSInteger)stackCount
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onExcerptMoveEnd({
                .id = [id UTF8String] ?: "",
                .x = static_cast<Float>(x),
                .y = static_cast<Float>(y),
                .clusterId = clusterId ? [clusterId UTF8String] : "",
                .stackCount = static_cast<Float>(stackCount)
            });
    }
}

- (void)emitExcerptPressWithId:(NSString *)id
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onExcerptPress({[id UTF8String]});
    }
}

- (void)emitCardDeleteWithId:(NSString *)id
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onCardDelete({[id UTF8String]});
    }
}

- (void)emitChangeCardColorWithId:(NSString *)id color:(NSString *)color
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onChangeCardColor({.id = [id UTF8String], .color = [color UTF8String]});
    }
}

- (void)emitUpdateCardCommentWithId:(NSString *)id comment:(NSString *)comment
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onUpdateCardComment({.id = [id UTF8String], .comment = [comment UTF8String]});
    }
}

- (void)emitHoldCardWithId:(NSString *)id
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onHoldCard({[id UTF8String]});
    }
}

- (void)emitTransformChangeWithPanX:(CGFloat)panX panY:(CGFloat)panY scale:(CGFloat)scale
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onTransformChange({
                .panX = static_cast<Float>(panX),
                .panY = static_cast<Float>(panY),
                .scale = static_cast<Float>(scale)
            });
    }
}

- (void)emitSplitRatioChangeWithRatio:(CGFloat)ratio
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onSplitRatioChange({.ratio = static_cast<Float>(ratio)});
    }
}

- (void)emitExtractExcerptWithText:(NSString *)text
                        pageNumber:(NSInteger)pageNumber
                             color:(NSString *)color
                           isTable:(BOOL)isTable
                           isImage:(BOOL)isImage
                          imageUrl:(NSString *)imageUrl
                                id:(NSString *)id
                                 x:(CGFloat)x
                                 y:(CGFloat)y
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onExtractExcerpt({
                .text = [text UTF8String] ?: "",
                .pageNumber = static_cast<Float>(pageNumber),
                .color = [color UTF8String] ?: "",
                .isTable = static_cast<bool>(isTable),
                .isImage = static_cast<bool>(isImage),
                .imageUrl = imageUrl ? [imageUrl UTF8String] : "",
                .id = id ? [id UTF8String] : "",
                .x = static_cast<Float>(x),
                .y = static_cast<Float>(y)
            });
    }
}

- (void)emitToggleSqueezeWithIsSqueezed:(BOOL)isSqueezed
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onToggleSqueeze({.isSqueezed = static_cast<bool>(isSqueezed)});
    }
}

- (void)emitUndoStateChangeWithCanUndo:(BOOL)canUndo canRedo:(BOOL)canRedo
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onUndoStateChange({.canUndo = static_cast<bool>(canUndo), .canRedo = static_cast<bool>(canRedo)});
    }
}

- (void)emitRequestDocumentSwitchWithDocumentId:(NSString *)documentId
                               sourcePageNumber:(NSInteger)sourcePageNumber
                                         cardId:(NSString *)cardId
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onRequestDocumentSwitch({
                .documentId = [documentId UTF8String],
                .sourcePageNumber = static_cast<Float>(sourcePageNumber),
                .cardId = [cardId UTF8String]
            });
    }
}

- (void)emitToggleImmersiveWithIsImmersive:(BOOL)isImmersive
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onToggleImmersive({.isImmersive = static_cast<bool>(isImmersive)});
    }
}

- (void)emitInkLinkCreateWithLinkJson:(NSString *)linkJson
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onInkLinkCreate({[linkJson UTF8String]});
    }
}

- (void)emitInkLinkDeleteWithId:(NSString *)id
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onInkLinkDelete({[id UTF8String]});
    }
}

- (void)emitPenStateChangeWithMode:(NSString *)mode
                             color:(NSString *)color
                         thickness:(CGFloat)thickness
                     favoritesJson:(NSString *)favoritesJson
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onPenStateChange({
                .mode = [mode UTF8String],
                .color = [color UTF8String],
                .thickness = static_cast<Float>(thickness),
                .favoritesJson = [favoritesJson UTF8String]
            });
    }
}

- (void)emitNotebookPageAddedWithId:(NSString *)id
                                  x:(CGFloat)x
                                  y:(CGFloat)y
                              width:(CGFloat)width
                             height:(CGFloat)height
                          pageStyle:(NSString *)pageStyle
                              title:(NSString *)title
{
    if (_eventEmitter) {
        std::static_pointer_cast<const ThinkspaceViewEventEmitter>(_eventEmitter)
            ->onNotebookPageAdded({
                .id = [id UTF8String] ?: "",
                .x = static_cast<Float>(x),
                .y = static_cast<Float>(y),
                .width = static_cast<Float>(width),
                .height = static_cast<Float>(height),
                .pageStyle = [pageStyle UTF8String] ?: "",
                .title = [title UTF8String] ?: ""
            });
    }
}

@end
