use 5.34.0;
use strict;
use warnings;
use feature 'try';
use Test::More;

subtest 'try/catch basic functionality' => sub {
    # Test try/catch with error
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
    like($try_catch_result, qr/Caught error: An error occurred/, 'try/catch works with error');

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
    is($try_no_error_result, "No error", 'try/catch with no error works');
};

subtest 'try expression lvalue returns follow standard Perl behavior' => sub {
    my $scalar;
    sub try_lvalue_scalar :lvalue {
        try { return $scalar }
        catch ($e) { }
    }

    try_lvalue_scalar = 123;
    is($scalar, undef, 'scalar lvalue return through try does not assign');

    my @array;
    sub try_lvalue_list :lvalue {
        try { return @array }
        catch ($e) { }
    }

    (try_lvalue_list) = (4, 5, 6);
    is_deeply(\@array, [], 'list lvalue return through try does not assign');
};

done_testing();
