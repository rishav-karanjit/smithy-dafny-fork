// Code generated by software.amazon.smithy.rust.codegen.smithy-rs. DO NOT EDIT.
#[allow(missing_docs)] // documentation missing in model
#[non_exhaustive]
#[derive(::std::clone::Clone, ::std::cmp::PartialEq, ::std::fmt::Debug)]
pub struct GetLongInput {
    #[allow(missing_docs)] // documentation missing in model
    pub value: ::std::option::Option<i64>,
}
impl GetLongInput {
    #[allow(missing_docs)] // documentation missing in model
    pub fn message(&self) -> ::std::option::Option<&i64> {
        self.value.as_ref()
    }
}
impl GetLongInput {
    /// Creates a new builder-style object to manufacture [`GetLongInput`](crate::operation::operation::GetLongInput).
    pub fn builder() -> crate::operation::get_long::builders::GetLongInputBuilder {
        crate::operation::get_long::builders::GetLongInputBuilder::default()
    }
}

/// A builder for [`GetLongInput`](crate::operation::operation::GetLongInput).
#[non_exhaustive]
#[derive(
    ::std::clone::Clone, ::std::cmp::PartialEq, ::std::default::Default, ::std::fmt::Debug,
)]
pub struct GetLongInputBuilder {
    pub(crate) value: ::std::option::Option<i64>,
}
impl GetLongInputBuilder {
    #[allow(missing_docs)] // documentation missing in model
    pub fn value(
        mut self,
        input: impl ::std::convert::Into<i64>,
    ) -> Self {
        self.value = ::std::option::Option::Some(input.into());
        self
    }
    #[allow(missing_docs)] // documentation missing in model
    pub fn set_value(
        mut self,
        input: ::std::option::Option<i64>,
    ) -> Self {
        self.value = input;
        self
    }
    #[allow(missing_docs)] // documentation missing in model
    pub fn get_value(&self) -> &::std::option::Option<i64> {
        &self.value
    }
    /// Consumes the builder and constructs a [`GetLongInput`](crate::operation::operation::GetLongInput).
    pub fn build(
        self,
    ) -> ::std::result::Result<
        crate::operation::get_long::GetLongInput,
        ::aws_smithy_types::error::operation::BuildError,
    > {
        ::std::result::Result::Ok(crate::operation::get_long::GetLongInput { value: self.value })
    }
}
