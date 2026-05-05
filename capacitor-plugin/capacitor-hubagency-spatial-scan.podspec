require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name             = 'capacitor-hubagency-spatial-scan'
  s.version          = package['version']
  s.summary          = package['description']
  s.license          = { :type => 'MIT' }
  s.homepage         = package['homepage'] || 'https://github.com/michelecampagnoni/capacitor-hubagency-spatial-scan'
  s.author           = { 'HubAgency' => 'm.campagnoni@hubique.it' }
  s.source           = { :git => 'https://github.com/michelecampagnoni/capacitor-hubagency-spatial-scan.git', :tag => s.version.to_s }
  s.source_files     = 'ios/Plugin/**/*.{swift,h,m}'
  s.ios.deployment_target = '14.0'
  s.swift_version    = '5.7'
  s.dependency 'Capacitor'
  s.frameworks       = 'ARKit', 'GLKit', 'OpenGLES', 'CoreGraphics', 'AVFoundation'
  s.pod_target_xcconfig = { 'SWIFT_VERSION' => '5.7' }
end
