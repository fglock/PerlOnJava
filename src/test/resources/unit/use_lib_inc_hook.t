use strict;
use warnings;
use Test::More tests => 4;

our $HOOK_CALLS = 0;

use lib map {
    my $hidden = $_;
    sub {
        ++$HOOK_CALLS;
        return unless $_[1] eq $hidden;
        die "hidden $_[1]\n";
    };
} qw{Cpanel/JSON/XS.pm JSON/XS.pm};

is(ref($INC[0]), 'CODE', 'use lib preserves first CODE @INC hook');
is(ref($INC[1]), 'CODE', 'use lib preserves second CODE @INC hook');

my $loaded = eval { require Cpanel::JSON::XS; 1 };
ok(!$loaded, 'require is stopped by CODE @INC hook installed via use lib');
like($@, qr/hidden Cpanel\/JSON\/XS\.pm/, 'hook die is reported by require');
