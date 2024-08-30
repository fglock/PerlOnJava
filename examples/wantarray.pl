# use 5.32.0;
# use feature 'say';
# use feature 'isa';

###################
# wantarray Tests

# Test wantarray in list context
my @list_context_result = sub { return wantarray ? "list" : "scalar" }->();
print "not " if "@list_context_result" ne "list"; say "ok # wantarray in list context returned '@list_context_result'";

# Test wantarray in scalar context
my $scalar_context_result = sub { return wantarray ? "list" : "scalar" }->();
print "not " if $scalar_context_result ne "scalar"; say "ok # wantarray in scalar context returned '$scalar_context_result'";

# Test wantarray in void context
my $void_context_result;
{
    sub { $void_context_result = wantarray ? "list" : "scalar" }->();
}
print "not " if $void_context_result ne "scalar"; say "ok # wantarray in void context returned '$void_context_result'";

# Test wantarray in nested subroutine
my @nested_list_context_result = sub { return sub { return wantarray ? "list" : "scalar" }->() }->();
print "not " if "@nested_list_context_result" ne "list"; say "ok # wantarray in nested list context returned '@nested_list_context_result'";

### my $nested_scalar_context_result = sub { return sub { return wantarray ? "list" : "scalar" }->() }->();
### print "not " if $nested_scalar_context_result ne "scalar"; say "ok # wantarray in nested scalar context returned '$nested_scalar_context_result'";
### 
### # Test wantarray with eval
### my $eval_result = eval 'wantarray ? "list" : "scalar"';
### print "not " if $eval_result ne "scalar"; say "ok # wantarray with eval returned '$eval_result'";
### 
### # Test wantarray with map
### my @map_result = map { wantarray ? "list" : "scalar" } (1);
### print "not " if "@map_result" ne "list"; say "ok # wantarray with map in list context returned '@map_result'";
### 
### my $map_scalar_result = scalar map { wantarray ? "list" : "scalar" } (1);
### print "not " if $map_scalar_result ne "scalar"; say "ok # wantarray with map in scalar context returned '$map_scalar_result'";
### 
### # Test wantarray with grep
### my @grep_result = grep { wantarray ? "list" : "scalar" } (1);
### print "not " if "@grep_result" ne "list"; say "ok # wantarray with grep in list context returned '@grep_result'";
### 
### my $grep_scalar_result = scalar grep { wantarray ? "list" : "scalar" } (1);
### print "not " if $grep_scalar_result ne "scalar"; say "ok # wantarray with grep in scalar context returned '$grep_scalar_result'";
### 
