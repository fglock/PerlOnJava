use strict;
use warnings;
use Test::More;

sub removed_sub { 24 }
sub compiled_caller { removed_sub() }

{
    no strict 'refs';
    undef *{'main::removed_sub'};
}

eval { compiled_caller() };
like($@, qr/^Undefined subroutine &main::removed_sub called/,
    'undefining a glob invalidates an already compiled direct call');
ok(!defined(&removed_sub), 'the undefined glob has no defined subroutine');

done_testing;
