use strict;
use warnings;
use Test::More tests => 4;

sub ordinary { 41 }
sub assignable :lvalue { our $slot }

my $ordinary = eval 'return 1; &ordinary = 2';
ok(!$ordinary, 'known non-lvalue sub assignment fails during eval compilation');
like($@, qr/Can't modify non-lvalue subroutine call/, 'compile failure is reported in $@');

my $assignable = eval 'return 1; &assignable = 2';
is($assignable, 1, 'unreachable assignment to known lvalue sub compiles');

{
    no strict 'refs';
    local *_probe = \&ordinary;
    my $probe = eval 'return 1; &_probe = 2';
    ok(!$probe, 'localized glob coderef retains compile-time lvalue metadata');
}
