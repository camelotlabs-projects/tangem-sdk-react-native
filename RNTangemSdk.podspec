require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "RNTangemSdk"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = <<-DESC
                  RNTangemSdk
                   DESC
  s.homepage     = package["repository"]["baseUrl"]
  s.license      = package["license"]
  s.author       = package["author"]
  
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => package["repository"]["url"], :tag => "#{s.version}" }
  
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  
  # some platform settings
  s.platform = :ios
  s.ios.deployment_target = '15.1'
  s.swift_version = '5.0'
  
  # deps
  #
  # Pinned to 3.9.0 — last 3.9.x available on the CocoaPods trunk.
  #
  # Higher TangemSdk versions (3.10.0, 3.11.0) ship a `module.modulemap` that
  # declares `TangemSdk_secp256k1` as a separate [system] module. When Pods
  # integrates the SDK into an app workspace, Xcode/Swift cannot resolve that
  # module without an explicit `SWIFT_INCLUDE_PATHS` pointing at the
  # modulemap directory, causing:
  #
  #   error: unable to resolve module dependency: 'TangemSdk_secp256k1'
  #
  # The 3.9.0 series ships everything as a single module, so the wrapper
  # works out of the box. The `ed25519_slip0010` curve (required for Hedera)
  # is available in 3.9.0, so there is no feature regression.
  #
  # Alternative for >= 3.10: add a `pod_target_xcconfig` here with
  # SWIFT_INCLUDE_PATHS = '$(PODS_ROOT)/TangemSdk/TangemSdk/TangemSdk'. Not
  # done in this PR to keep the change minimal; happy to bump if maintainers
  # prefer that route.
  s.dependency 'TangemSdk', "3.9.0"
  # new archt deps
  install_modules_dependencies(s)
  
end
