// Package Wrappers
// Dafny module Wrappers compiled into Go

package Wrappers

import (
	_System "System_"
	_dafny "dafny"
	os "os"
)

var _ _dafny.Dummy__
var _ = os.Args
var _ _System.Dummy__

type Dummy__ struct{}

// Definition of class Default__
type Default__ struct {
	dummy byte
}

func New_Default___() *Default__ {
	_this := Default__{}

	return &_this
}

type CompanionStruct_Default___ struct {
}

var Companion_Default___ = CompanionStruct_Default___{}

func (_this *Default__) Equals(other *Default__) bool {
	return _this == other
}

func (_this *Default__) EqualsGeneric(x interface{}) bool {
	other, ok := x.(*Default__)
	return ok && _this.Equals(other)
}

func (*Default__) String() string {
	return "Wrappers.Default__"
}
func (_this *Default__) ParentTraits_() []*_dafny.TraitID {
	return [](*_dafny.TraitID){}
}

var _ _dafny.TraitOffspring = &Default__{}

func (_static *CompanionStruct_Default___) Need(condition bool, error_ interface{}) Outcome {
	if condition {
		return Companion_Outcome_.Create_Pass_()
	} else {
		return Companion_Outcome_.Create_Fail_(error_)
	}
}

// End of class Default__

// Definition of datatype Option
type Option struct {
	Data_Option_
}

func (_this Option) Get() Data_Option_ {
	return _this.Data_Option_
}

type Data_Option_ interface {
	isOption()
}

type CompanionStruct_Option_ struct {
}

var Companion_Option_ = CompanionStruct_Option_{}

type Option_None struct {
}

func (Option_None) isOption() {}

func (CompanionStruct_Option_) Create_None_() Option {
	return Option{Option_None{}}
}

func (_this Option) Is_None() bool {
	_, ok := _this.Get().(Option_None)
	return ok
}

type Option_Some struct {
	Value interface{}
}

func (Option_Some) isOption() {}

func (CompanionStruct_Option_) Create_Some_(Value interface{}) Option {
	return Option{Option_Some{Value}}
}

func (_this Option) Is_Some() bool {
	_, ok := _this.Get().(Option_Some)
	return ok
}

func (CompanionStruct_Option_) Default() Option {
	return Companion_Option_.Create_None_()
}

func (_this Option) Dtor_value() interface{} {
	return _this.Get().(Option_Some).Value
}

func (_this Option) String() string {
	switch data := _this.Get().(type) {
	case nil:
		return "null"
	case Option_None:
		{
			return "Wrappers.Option.None"
		}
	case Option_Some:
		{
			return "Wrappers.Option.Some" + "(" + _dafny.String(data.Value) + ")"
		}
	default:
		{
			return "<unexpected>"
		}
	}
}

func (_this Option) Equals(other Option) bool {
	switch data1 := _this.Get().(type) {
	case Option_None:
		{
			_, ok := other.Get().(Option_None)
			return ok
		}
	case Option_Some:
		{
			data2, ok := other.Get().(Option_Some)
			return ok && _dafny.AreEqual(data1.Value, data2.Value)
		}
	default:
		{
			return false // unexpected
		}
	}
}

func (_this Option) EqualsGeneric(other interface{}) bool {
	typed, ok := other.(Option)
	return ok && _this.Equals(typed)
}

func Type_Option_() _dafny.TypeDescriptor {
	return type_Option_{}
}

type type_Option_ struct {
}

func (_this type_Option_) Default() interface{} {
	return Companion_Option_.Default()
}

func (_this type_Option_) String() string {
	return "Wrappers.Option"
}
func (_this Option) ParentTraits_() []*_dafny.TraitID {
	return [](*_dafny.TraitID){}
}

var _ _dafny.TraitOffspring = Option{}

func (_this Option) ToResult() Result {
	{
		var _source0 Option = _this
		_ = _source0
		if _source0.Is_None() {
			return Companion_Result_.Create_Failure_(_dafny.SeqOfString("Option is None"))
		} else {
			var _0___mcc_h0 interface{} = _source0.Get().(Option_Some).Value
			_ = _0___mcc_h0
			var _1_v interface{} = _0___mcc_h0
			_ = _1_v
			return Companion_Result_.Create_Success_(_1_v)
		}
	}
}
func (_this Option) UnwrapOr(default_ interface{}) interface{} {
	{
		var _source1 Option = _this
		_ = _source1
		if _source1.Is_None() {
			return default_
		} else {
			var _2___mcc_h0 interface{} = _source1.Get().(Option_Some).Value
			_ = _2___mcc_h0
			var _3_v interface{} = _2___mcc_h0
			_ = _3_v
			return _3_v
		}
	}
}
func (_this Option) IsFailure() bool {
	{
		return (_this).Is_None()
	}
}
func (_this Option) PropagateFailure() Option {
	{
		return Companion_Option_.Create_None_()
	}
}
func (_this Option) Extract() interface{} {
	{
		return (_this).Dtor_value()
	}
}

// End of datatype Option

// Definition of datatype Result
type Result struct {
	Data_Result_
}

func (_this Result) Get() Data_Result_ {
	return _this.Data_Result_
}

type Data_Result_ interface {
	isResult()
}

type CompanionStruct_Result_ struct {
}

var Companion_Result_ = CompanionStruct_Result_{}

type Result_Success struct {
	Value interface{}
}

func (Result_Success) isResult() {}

func (CompanionStruct_Result_) Create_Success_(Value interface{}) Result {
	return Result{Result_Success{Value}}
}

func (_this Result) Is_Success() bool {
	_, ok := _this.Get().(Result_Success)
	return ok
}

type Result_Failure struct {
	Error interface{}
}

func (Result_Failure) isResult() {}

func (CompanionStruct_Result_) Create_Failure_(Error interface{}) Result {
	return Result{Result_Failure{Error}}
}

func (_this Result) Is_Failure() bool {
	_, ok := _this.Get().(Result_Failure)
	return ok
}

func (CompanionStruct_Result_) Default(_default_T interface{}) Result {
	return Companion_Result_.Create_Success_(_default_T)
}

func (_this Result) Dtor_value() interface{} {
	return _this.Get().(Result_Success).Value
}

func (_this Result) Dtor_error() interface{} {
	return _this.Get().(Result_Failure).Error
}

func (_this Result) String() string {
	switch data := _this.Get().(type) {
	case nil:
		return "null"
	case Result_Success:
		{
			return "Wrappers.Result.Success" + "(" + _dafny.String(data.Value) + ")"
		}
	case Result_Failure:
		{
			return "Wrappers.Result.Failure" + "(" + _dafny.String(data.Error) + ")"
		}
	default:
		{
			return "<unexpected>"
		}
	}
}

func (_this Result) Equals(other Result) bool {
	switch data1 := _this.Get().(type) {
	case Result_Success:
		{
			data2, ok := other.Get().(Result_Success)
			return ok && _dafny.AreEqual(data1.Value, data2.Value)
		}
	case Result_Failure:
		{
			data2, ok := other.Get().(Result_Failure)
			return ok && _dafny.AreEqual(data1.Error, data2.Error)
		}
	default:
		{
			return false // unexpected
		}
	}
}

func (_this Result) EqualsGeneric(other interface{}) bool {
	typed, ok := other.(Result)
	return ok && _this.Equals(typed)
}

func Type_Result_(Type_T_ _dafny.TypeDescriptor) _dafny.TypeDescriptor {
	return type_Result_{Type_T_}
}

type type_Result_ struct {
	Type_T_ _dafny.TypeDescriptor
}

func (_this type_Result_) Default() interface{} {
	Type_T_ := _this.Type_T_
	_ = Type_T_
	return Companion_Result_.Default(Type_T_.Default())
}

func (_this type_Result_) String() string {
	return "Wrappers.Result"
}
func (_this Result) ParentTraits_() []*_dafny.TraitID {
	return [](*_dafny.TraitID){}
}

var _ _dafny.TraitOffspring = Result{}

func (_this Result) ToOption() Option {
	{
		var _source2 Result = _this
		_ = _source2
		if _source2.Is_Success() {
			var _4___mcc_h0 interface{} = _source2.Get().(Result_Success).Value
			_ = _4___mcc_h0
			var _5_s interface{} = _4___mcc_h0
			_ = _5_s
			return Companion_Option_.Create_Some_(_5_s)
		} else {
			var _6___mcc_h1 interface{} = _source2.Get().(Result_Failure).Error
			_ = _6___mcc_h1
			var _7_e interface{} = _6___mcc_h1
			_ = _7_e
			return Companion_Option_.Create_None_()
		}
	}
}
func (_this Result) UnwrapOr(default_ interface{}) interface{} {
	{
		var _source3 Result = _this
		_ = _source3
		if _source3.Is_Success() {
			var _8___mcc_h0 interface{} = _source3.Get().(Result_Success).Value
			_ = _8___mcc_h0
			var _9_s interface{} = _8___mcc_h0
			_ = _9_s
			return _9_s
		} else {
			var _10___mcc_h1 interface{} = _source3.Get().(Result_Failure).Error
			_ = _10___mcc_h1
			var _11_e interface{} = _10___mcc_h1
			_ = _11_e
			return default_
		}
	}
}
func (_this Result) IsFailure() bool {
	{
		return (_this).Is_Failure()
	}
}
func (_this Result) PropagateFailure() Result {
	{
		return Companion_Result_.Create_Failure_((_this).Dtor_error())
	}
}
func (_this Result) MapFailure(reWrap func(interface{}) interface{}) Result {
	{
		var _source4 Result = _this
		_ = _source4
		if _source4.Is_Success() {
			var _12___mcc_h0 interface{} = _source4.Get().(Result_Success).Value
			_ = _12___mcc_h0
			var _13_s interface{} = _12___mcc_h0
			_ = _13_s
			return Companion_Result_.Create_Success_(_13_s)
		} else {
			var _14___mcc_h1 interface{} = _source4.Get().(Result_Failure).Error
			_ = _14___mcc_h1
			var _15_e interface{} = _14___mcc_h1
			_ = _15_e
			return Companion_Result_.Create_Failure_((reWrap)(_15_e))
		}
	}
}
func (_this Result) Extract() interface{} {
	{
		return (_this).Dtor_value()
	}
}

// End of datatype Result

// Definition of datatype Outcome
type Outcome struct {
	Data_Outcome_
}

func (_this Outcome) Get() Data_Outcome_ {
	return _this.Data_Outcome_
}

type Data_Outcome_ interface {
	isOutcome()
}

type CompanionStruct_Outcome_ struct {
}

var Companion_Outcome_ = CompanionStruct_Outcome_{}

type Outcome_Pass struct {
}

func (Outcome_Pass) isOutcome() {}

func (CompanionStruct_Outcome_) Create_Pass_() Outcome {
	return Outcome{Outcome_Pass{}}
}

func (_this Outcome) Is_Pass() bool {
	_, ok := _this.Get().(Outcome_Pass)
	return ok
}

type Outcome_Fail struct {
	Error interface{}
}

func (Outcome_Fail) isOutcome() {}

func (CompanionStruct_Outcome_) Create_Fail_(Error interface{}) Outcome {
	return Outcome{Outcome_Fail{Error}}
}

func (_this Outcome) Is_Fail() bool {
	_, ok := _this.Get().(Outcome_Fail)
	return ok
}

func (CompanionStruct_Outcome_) Default() Outcome {
	return Companion_Outcome_.Create_Pass_()
}

func (_this Outcome) Dtor_error() interface{} {
	return _this.Get().(Outcome_Fail).Error
}

func (_this Outcome) String() string {
	switch data := _this.Get().(type) {
	case nil:
		return "null"
	case Outcome_Pass:
		{
			return "Wrappers.Outcome.Pass"
		}
	case Outcome_Fail:
		{
			return "Wrappers.Outcome.Fail" + "(" + _dafny.String(data.Error) + ")"
		}
	default:
		{
			return "<unexpected>"
		}
	}
}

func (_this Outcome) Equals(other Outcome) bool {
	switch data1 := _this.Get().(type) {
	case Outcome_Pass:
		{
			_, ok := other.Get().(Outcome_Pass)
			return ok
		}
	case Outcome_Fail:
		{
			data2, ok := other.Get().(Outcome_Fail)
			return ok && _dafny.AreEqual(data1.Error, data2.Error)
		}
	default:
		{
			return false // unexpected
		}
	}
}

func (_this Outcome) EqualsGeneric(other interface{}) bool {
	typed, ok := other.(Outcome)
	return ok && _this.Equals(typed)
}

func Type_Outcome_() _dafny.TypeDescriptor {
	return type_Outcome_{}
}

type type_Outcome_ struct {
}

func (_this type_Outcome_) Default() interface{} {
	return Companion_Outcome_.Default()
}

func (_this type_Outcome_) String() string {
	return "Wrappers.Outcome"
}
func (_this Outcome) ParentTraits_() []*_dafny.TraitID {
	return [](*_dafny.TraitID){}
}

var _ _dafny.TraitOffspring = Outcome{}

func (_this Outcome) IsFailure() bool {
	{
		return (_this).Is_Fail()
	}
}
func (_this Outcome) PropagateFailure() Result {
	{
		return Companion_Result_.Create_Failure_((_this).Dtor_error())
	}
}

// End of datatype Outcome
