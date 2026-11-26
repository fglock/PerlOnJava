use strict;
use warnings;
use Test::More tests => 8;

sub rejects_readonly_return {
    my ($success, $error, $context) = @_;
    ok(!$success, "explicit readonly return is rejected in $context context");
    like($error, qr/Can't return a readonly value from lvalue subroutine/,
        "$context lvalue return reports Perl readonly diagnostic");
}

my $success = eval { +sub :lvalue { return 3 }->() = 4; 1 };
my $error = $@;
rejects_readonly_return($success, $error, 'static scalar lvalue');

$success = eval { (+sub :lvalue { return 3 }->()) = 4; 1 };
$error = $@;
rejects_readonly_return($success, $error, 'static list lvalue');

$success = eval q{ +sub :lvalue { return 3 }->() = 4; 1 };
$error = $@;
rejects_readonly_return($success, $error, 'dynamic scalar lvalue');

$success = eval q{ (+sub :lvalue { return 3 }->()) = 4; 1 };
$error = $@;
rejects_readonly_return($success, $error, 'dynamic list lvalue');
