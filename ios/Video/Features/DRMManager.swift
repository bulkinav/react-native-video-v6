//
//  DRMManager.swift
//  react-native-video
//
//  Created by Krzysztof Moch on 13/08/2024.
//

import AVFoundation

class DRMManager: NSObject, DRMManagerSpec {
    static let queue = DispatchQueue(label: "RNVideoContentKeyDelegateQueue")
    let contentKeySession: AVContentKeySession?

    var drmParams: DRMParams?
    var reactTag: NSNumber?
    var onVideoError: RCTDirectEventBlock?
    var onGetLicense: RCTDirectEventBlock?

    /// Licenses handled by onGetLicense (from JS side)
    var pendingLicenses: [String: AVContentKeyRequest] = [:]

    override init() {
        #if targetEnvironment(simulator)
            contentKeySession = nil
            super.init()
        #else
            contentKeySession = AVContentKeySession(keySystem: .fairPlayStreaming)
            super.init()

            contentKeySession?.setDelegate(self, queue: DRMManager.queue)
        #endif
    }

    func createContentKeyRequest(
        asset: AVContentKeyRecipient,
        drmParams: DRMParams?,
        reactTag: NSNumber?,
        onVideoError: RCTDirectEventBlock?,
        onGetLicense: RCTDirectEventBlock?
    ) {
        self.reactTag = reactTag
        self.onVideoError = onVideoError
        self.onGetLicense = onGetLicense
        self.drmParams = drmParams
        logFairPlay("setup type=\(drmParams?.type ?? "none") certificate=\(drmParams?.certificateUrl != nil) license=\(drmParams?.licenseServer != nil) jsLicense=\(onGetLicense != nil) session=\(contentKeySession != nil)")

        if drmParams?.type != "fairplay" {
            self.onVideoError?([
                "error": RCTVideoErrorHandler.createError(from: RCTVideoError.unsupportedDRMType),
                "target": self.reactTag as Any,
            ])
            return
        }

        #if targetEnvironment(simulator)
            DebugLog("Simulator is not supported for FairPlay DRM.")
            self.onVideoError?([
                "error": RCTVideoErrorHandler.createError(from: RCTVideoError.simulatorDRMNotSupported),
                "target": self.reactTag as Any,
            ])
        #endif

        contentKeySession?.addContentKeyRecipient(asset)
    }

    // MARK: - Internal

    /// Выводит этап FairPlay в Xcode только в DEBUG, без ключей, тела ответа и полных URL.
    func logFairPlay(_ message: @autoclosure () -> String) {
        #if DEBUG
            NSLog("[FairPlay][player=%@] %@", reactTag?.stringValue ?? "-", message())
        #endif
    }

    /// Выводит домен и код ошибки и её вложенной причины, не раскрывая URL из userInfo.
    func logFairPlayError(_ error: Error, stage: String) {
        let nativeError = error as NSError
        logFairPlay("\(stage) error domain=\(nativeError.domain) code=\(nativeError.code)")
        if let underlyingError = nativeError.userInfo[NSUnderlyingErrorKey] as? NSError {
            logFairPlay("\(stage) underlying domain=\(underlyingError.domain) code=\(underlyingError.code)")
        }
    }

    func handleContentKeyRequest(keyRequest: AVContentKeyRequest) {
        logFairPlay("key-request received status=\(keyRequest.status.rawValue)")
        Task {
            do {
                if drmParams?.localSourceEncryptionKeyScheme != nil {
                    #if os(iOS)
                        try keyRequest.respondByRequestingPersistableContentKeyRequestAndReturnError()
                        return
                    #else
                        throw RCTVideoError.offlineDRMNotSupported
                    #endif
                }

                try await processContentKeyRequest(keyRequest: keyRequest)
            } catch {
                handleError(error, for: keyRequest)
            }
        }
    }

    func finishProcessingContentKeyRequest(keyRequest: AVContentKeyRequest, license: Data) throws {
        logFairPlay("ckc-submit bytes=\(license.count)")
        let keyResponse = AVContentKeyResponse(fairPlayStreamingKeyResponseData: license)
        keyRequest.processContentKeyResponse(keyResponse)
    }

    func handleError(_ error: Error, for keyRequest: AVContentKeyRequest) {
        logFairPlayError(error, stage: "key-request")
        let rctError: RCTVideoError
        if let videoError = error as? RCTVideoError {
            logFairPlay("key-request rnvCode=\(videoError.errorCode)")
            // handle RCTVideoError errors
            rctError = videoError

            DispatchQueue.main.async { [weak self] in
                self?.onVideoError?([
                    "error": RCTVideoErrorHandler.createError(from: rctError),
                    "target": self?.reactTag as Any,
                ])
            }
        } else {
            let err = error as NSError

            // handle Other errors
            DispatchQueue.main.async { [weak self] in
                self?.onVideoError?([
                    "error": [
                        "code": err.code,
                        "localizedDescription": err.localizedDescription,
                        "localizedFailureReason": err.localizedFailureReason ?? "",
                        "localizedRecoverySuggestion": err.localizedRecoverySuggestion ?? "",
                        "domain": err.domain,
                    ],
                    "target": self?.reactTag as Any,
                ])
            }
        }

        keyRequest.processContentKeyResponseError(error)
        contentKeySession?.expire()
    }

    // MARK: - Private

    private func processContentKeyRequest(keyRequest: AVContentKeyRequest) async throws {
        guard let assetId = getAssetId(keyRequest: keyRequest),
              let assetIdData = assetId.data(using: .utf8) else {
            throw RCTVideoError.invalidContentId
        }

        logFairPlay("content-id source=\(drmParams?.contentId != nil ? "override" : "skd") bytes=\(assetIdData.count) containsPath=\(assetId.contains("/"))")
        let appCertificate = try await requestApplicationCertificate()
        logFairPlay("spc-generate certificateBytes=\(appCertificate.count)")
        let spcData = try await keyRequest.makeStreamingContentKeyRequestData(forApp: appCertificate, contentIdentifier: assetIdData)

        logFairPlay("spc-generated bytes=\(spcData.count)")
        if onGetLicense != nil {
            logFairPlay("license-request via=js")
            try await requestLicenseFromJS(spcData: spcData, assetId: assetId, keyRequest: keyRequest)
        } else {
            let license = try await requestLicense(spcData: spcData)
            try finishProcessingContentKeyRequest(keyRequest: keyRequest, license: license)
        }
    }

    private func requestApplicationCertificate() async throws -> Data {
        guard let urlString = drmParams?.certificateUrl,
              let url = URL(string: urlString) else {
            throw RCTVideoError.noCertificateURL
        }

        logFairPlay("certificate-request host=\(url.host ?? "-") base64=\(drmParams?.base64Certificate == true)")
        let (data, response) = try await URLSession.shared.data(from: url)
        logFairPlay("certificate-response status=\((response as? HTTPURLResponse)?.statusCode ?? 0) mime=\(response.mimeType ?? "-") bytes=\(data.count)")

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw RCTVideoError.noCertificateData
        }

        if drmParams?.base64Certificate == true {
            guard let certData = Data(base64Encoded: data) else {
                throw RCTVideoError.noCertificateData
            }
            logFairPlay("certificate-decoded bytes=\(certData.count)")
            return certData
        }

        return data
    }

    private func requestLicense(spcData: Data) async throws -> Data {
        guard let licenseServerUrlString = drmParams?.licenseServer,
              let licenseServerUrl = URL(string: licenseServerUrlString) else {
            throw RCTVideoError.noLicenseServerURL
        }

        var request = URLRequest(url: licenseServerUrl)
        request.httpMethod = "POST"
        request.httpBody = spcData

        if let headers = drmParams?.headers {
            for (key, value) in headers {
                if let stringValue = value as? String {
                    request.setValue(stringValue, forHTTPHeaderField: key)
                }
            }
        }

        logFairPlay("license-request via=native host=\(licenseServerUrl.host ?? "-") spcBytes=\(spcData.count) contentType=\(request.value(forHTTPHeaderField: "Content-Type") ?? "unset")")
        let (data, response) = try await URLSession.shared.data(for: request)
        logFairPlay("license-response status=\((response as? HTTPURLResponse)?.statusCode ?? 0) mime=\(response.mimeType ?? "-") bytes=\(data.count)")

        guard let httpResponse = response as? HTTPURLResponse else {
            throw RCTVideoError.licenseRequestFailed(0)
        }

        guard httpResponse.statusCode == 200 else {
            throw RCTVideoError.licenseRequestFailed(httpResponse.statusCode)
        }

        guard !data.isEmpty else {
            throw RCTVideoError.noDataFromLicenseRequest
        }

        return data
    }

    private func getAssetId(keyRequest: AVContentKeyRequest) -> String? {
        if let assetId = drmParams?.contentId {
            return assetId
        }

        if let url = keyRequest.identifier as? String {
            return url.replacingOccurrences(of: "skd://", with: "")
        }

        return nil
    }
}
