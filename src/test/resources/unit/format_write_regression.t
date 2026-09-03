use strict;
use warnings;
use Test::More tests => 4;

format STDOUT =
.

my $error;
{
    local $@;
    eval { write };
    $error = $@;
}

ok !$error, 'write registers and executes a declared format';

open(UNDEF, '>', 'format_write_active_format.tmp') or die "open: $!";
select +(select(UNDEF), $~ = 'UNDEFFORMAT')[0];
format UNDEFFORMAT =
@
undef *UNDEFFORMAT
.
eval { write UNDEF };
ok !$@, 'write uses the handle active format after its format glob is freed';

my $old = select UNDEF;
is $~, 'UNDEFFORMAT', 'current format remains associated with its handle';
{
    local $~ = 'LOCAL_FORMAT';
    is $~, 'LOCAL_FORMAT', 'local current format applies to the selected handle';
}
select $old;
close UNDEF;
unlink 'format_write_active_format.tmp';
