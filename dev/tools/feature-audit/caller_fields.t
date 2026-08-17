use strict;
use warnings;
use Test::More tests => 4;

sub audit_caller_fields {
    my @fields = caller(0);
    is(scalar(@fields), 11, 'caller returns the full 11-field tuple');
    is($fields[0], 'main', 'caller package is preserved');
    like($fields[1], qr/caller_fields\.t$/, 'caller filename is preserved');
    is($fields[3], 'main::audit_caller_fields', 'caller subroutine name is preserved');
}

audit_caller_fields();
