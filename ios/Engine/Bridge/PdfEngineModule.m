#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(PdfEngineModule, NSObject)

RCT_EXTERN_METHOD(openDocument:(NSString *)uriOrPath
                  password:(NSString *)password
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(extractText:(NSString *)docId
                  pageIndex:(nonnull NSNumber *)pageIndex
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(analyzePage:(NSString *)docId
                  pageIndex:(nonnull NSNumber *)pageIndex
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(searchDocument:(NSString *)docId
                  query:(NSString *)query
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(renderPage:(NSString *)docId
                  pageIndex:(nonnull NSNumber *)pageIndex
                  scale:(nonnull NSNumber *)scale
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(renderPageRegion:(NSString *)docId
                  pageIndex:(nonnull NSNumber *)pageIndex
                  left:(nonnull NSNumber *)left
                  top:(nonnull NSNumber *)top
                  right:(nonnull NSNumber *)right
                  bottom:(nonnull NSNumber *)bottom
                  scale:(nonnull NSNumber *)scale
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getSelectionGeometry:(NSString *)docId
                  pageIndex:(nonnull NSNumber *)pageIndex
                  startX:(nonnull NSNumber *)startX
                  startY:(nonnull NSNumber *)startY
                  endX:(nonnull NSNumber *)endX
                  endY:(nonnull NSNumber *)endY
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(processDocument:(NSString *)docId
                  options:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(pickPdfFile:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(closeDocument:(NSString *)docId
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(shutdown:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

@end
