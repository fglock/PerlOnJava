use strict;
use warnings;
use Test::More tests => 5;

# These regressions only surface on the bytecode-interpreter path. The
# unreachable dynamic goto is enough to force interpreter fallback for each sub.

sub captured_lvalue_assignment {
    my $in;
    sub pr833_get_lex : lvalue { $in }
    $in = 5;
    pr833_get_lex() = 7;
    return $in;

    my $f = sub { 1 };
    goto $f;
}

is(captured_lvalue_assignment(), 7,
    'interpreter assignment preserves scalar slot captured by lvalue sub');

sub captured_closure_assignment {
    my $value = 1;
    my $get = sub { $value };
    $value = 2;
    return $get->();

    my $f = sub { 1 };
    goto $f;
}

is(captured_closure_assignment(), 2,
    'interpreter assignment preserves scalar slot captured by anonymous closure');

sub our_assignment_chain {
    our $PR833_X1 = '';
    our $PR833_X2 = '';
    my ($a, $b) = qw(abcd wxyz);
    $PR833_X1 = ($PR833_X2 = sprintf('%s%s', $a, $b));
    return "$PR833_X1|$PR833_X2";

    my $f = sub { 1 };
    goto $f;
}

is(our_assignment_chain(), 'abcdwxyz|abcdwxyz',
    'interpreter assignment to our scalars writes package globals');

sub local_our_assignment_chain {
    local our $PR833_LOCAL_X1 = '';
    local our $PR833_LOCAL_X2 = '';
    my ($a, $b) = qw(abcd wxyz);
    $PR833_LOCAL_X1 = ($PR833_LOCAL_X2 = sprintf('%s%s', $a, $b));
    return "$PR833_LOCAL_X1|$PR833_LOCAL_X2";

    my $f = sub { 1 };
    goto $f;
}

is(local_our_assignment_chain(), 'abcdwxyz|abcdwxyz',
    'interpreter assignment to localized our scalars writes localized globals');

sub our_declared_package_assignment {
    package PR833::Other;
    our $VALUE = '';
    $VALUE = 'set in declared package';
    return $VALUE;

    my $f = sub { 1 };
    goto $f;
}

is(our_declared_package_assignment(), 'set in declared package',
    'interpreter assignment honours the declaring package of our scalar');
