use 5.34.0;
use strict;
use warnings;
use feature 'say';
use feature 'try';

# Test try/catch
sub test_try_catch {
    my $result;
    try {
        die "An error occurred";
    }
    catch ($e) {
        $result = "Caught error: $e";
    }
    # return $result;   # implicit return
}

my $try_catch_result = test_try_catch();
print "not " if $try_catch_result !~ "Caught error: An error occurred"; say "ok # try/catch works";

# Test try/catch with no error
sub test_try_no_error {
    my $result;
    try {
        # No error here
        $result = "No error";
    }
    catch ($e) {
        $result = "Caught error: $e";
    }
    return $result;
}

my $try_no_error_result = test_try_no_error();
print "not " if $try_no_error_result ne "No error"; say "ok # try/catch with no error works";


