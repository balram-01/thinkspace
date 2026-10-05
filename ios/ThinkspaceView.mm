#import "ThinkspaceView.h"

#import <React/RCTConversions.h>

#import <react/renderer/components/ThinkspaceViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/ThinkspaceViewSpec/Props.h>
#import <react/renderer/components/ThinkspaceViewSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

@implementation ThinkspaceView {
    UIView * _view;
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

    _view = [[UIView alloc] init];

    self.contentView = _view;
  }

  return self;
}

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps
{
    [super updateProps:props oldProps:oldProps];
}

@end
