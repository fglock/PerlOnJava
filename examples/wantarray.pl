# use 5.32.0;
# use feature 'say';
# use feature 'isa';

###################
# wantarray Tests

sub wantarray_as_string {
    !defined $_[0] ? "void" : $_[0] ? "list" : "scalar"
}

# Test wantarray in list context
my @list_context_result = sub { return wantarray_as_string(wantarray) }->();
print "not " if "@list_context_result" ne "list"; say "ok # wantarray in list context returned '@list_context_result'";

# Test wantarray in scalar context
my $scalar_context_result = sub { return wantarray_as_string(wantarray) }->();
print "not " if $scalar_context_result ne "scalar"; say "ok # wantarray in scalar context returned '$scalar_context_result'";

# Test wantarray in void context
my $void_context_result;
{
    sub { $void_context_result = wantarray_as_string(wantarray) }->();
}
print "not " if $void_context_result ne "void"; say "ok # wantarray in void context returned '$void_context_result'";

# Test wantarray in nested subroutine
my @nested_list_context_result = sub { return sub { return wantarray_as_string(wantarray) }->() }->();
print "not " if "@nested_list_context_result" ne "list"; say "ok # wantarray in nested list context returned '@nested_list_context_result'";

my $nested_scalar_context_result = sub { return sub { return wantarray_as_string(wantarray) }->() }->();
print "not " if $nested_scalar_context_result ne "scalar"; say "ok # wantarray in nested scalar context returned '$nested_scalar_context_result'";

# Test wantarray with eval
my $eval_result = eval 'wantarray_as_string(wantarray)';
print "not " if $eval_result ne "scalar"; say "ok # wantarray with eval returned '$eval_result'";

my @eval_result = eval 'wantarray_as_string(wantarray)';
print "not " if "@eval_result" ne "list"; say "ok # wantarray with eval returned '@eval_result'";
