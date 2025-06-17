use strict;
use feature 'say';
use feature 'isa';
use Test::More;

sub wantarray_as_string {
    !defined $_[0] ? "void" : $_[0] ? "list" : "scalar"
}

# Test wantarray in list context
my @list_context_result = sub { return wantarray_as_string(wantarray) }->();
is("@list_context_result", "list", 'wantarray in list context');

# Test wantarray in scalar context
my $scalar_context_result = sub { return wantarray_as_string(wantarray) }->();
is($scalar_context_result, "scalar", 'wantarray in scalar context');

# Test wantarray in void context
my $void_context_result;
{
    sub { $void_context_result = wantarray_as_string(wantarray) }->();
}
is($void_context_result, "void", 'wantarray in void context');

# Test wantarray in nested subroutine
my @nested_list_context_result = sub { return sub { return wantarray_as_string(wantarray) }->() }->();
is("@nested_list_context_result", "list", 'wantarray in nested list context');

my $nested_scalar_context_result = sub { return sub { return wantarray_as_string(wantarray) }->() }->();
is($nested_scalar_context_result, "scalar", 'wantarray in nested scalar context');

# Test wantarray with eval
my $eval_result = eval 'wantarray_as_string(wantarray)';
is($eval_result, "scalar", 'wantarray with eval in scalar context');

my @eval_result = eval 'wantarray_as_string(wantarray)';
is("@eval_result", "list", 'wantarray with eval in list context');

done_testing();
