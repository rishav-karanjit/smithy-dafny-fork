// Code generated by software.amazon.smithy.rust.codegen.smithy-rs. DO NOT EDIT.
impl super::Client {
    /// Constructs a fluent builder for the [`GetEnumV2`](crate::operation::get_enum_v2::builders::GetEnumV2FluentBuilder) operation.
    ///
    /// - The fluent builder is configurable:
    ///   - [`value(SimpleEnumV2Shape)`](crate::operation::get_enum_v2::builders::GetEnumV2FluentBuilder::value) / [`set_value(Option<SimpleEnumV2Shape>)`](crate::operation::get_enum_v2::builders::GetEnumV2FluentBuilder::set_value):(undocumented)<br>
    /// - On success, responds with [`GetEnumV2Output`](crate::operation::get_enum_v2::GetEnumV2Output) with field(s):
    ///   - [`value(Option<EnumV2>)`](crate::operation::get_enum_v2::GetEnumV2Output::value): (undocumented)
    /// - On failure, responds with [`SdkError<GetEnumV2Error>`](crate::operation::get_enum_v2::GetEnumV2Error)
    pub fn get_enum_v2(&self) -> crate::operation::get_enum_v2::builders::GetEnumV2FluentBuilder {
        crate::operation::get_enum_v2::builders::GetEnumV2FluentBuilder::new(self.clone())
    }
}
