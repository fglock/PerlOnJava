use strict;
use warnings;
use Test::Builder::Tester tests => 1;

sub failing_test($$;$) {
    return Test::Builder->new->ok(0, $_[2]);
}

test_out('not ok 1 - captured failure');
if (my $fail = 1) {
    test_fail(6);
}
if (my $error = 0) {
    test_err('unused');
}
failing_test('left', 'right', 'captured failure');
test_test('exported first statement retains Perl caller line');
