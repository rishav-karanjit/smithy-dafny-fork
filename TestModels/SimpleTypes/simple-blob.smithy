namespace simple.types.blob

@aws.polymorph#localService(
  sdkId: "SimpleBlob",
  config: SimpleBlobConfig,
)
service SimpleTypesBlob {
  version: "2021-11-01",
  resources: [],
  operations: [ GetBlob ],
  errors: [],
}

structure SimpleBlobConfig {}

operation GetBlob {
  input: GetBlobInput,
  output: GetBlobOutput,
}

structure GetBlobInput {
  value: Blob
}

structure GetBlobOutput {
  value: Blob
}