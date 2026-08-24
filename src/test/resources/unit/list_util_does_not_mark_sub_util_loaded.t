use strict;
use warnings;

BEGIN {
    unshift @INC, sub {
        my ($hook, $file) = @_;
        die "Sub::Util intentionally hidden\n" if $file eq 'Sub/Util.pm';
        if ($file eq 'Sub/Name.pm') {
            my $source = <<'MODULE';
package Sub::Name;
sub subname {
    $::sub_name_calls++;
    return $_[1];
}
1;
MODULE
            open my $fh, '<', \$source or die $!;
            return $fh;
        }
        return;
    };
}

use Test::More;
use List::Util ();

my ($su, $sn);
$su = $INC{'Sub/Util.pm'} && defined &Sub::Util::set_subname
    or $sn = $INC{'Sub/Name.pm'}
    or $su = eval { require Sub::Util; } && defined &Sub::Util::set_subname
    or $sn = eval { require Sub::Name; };

my $subname = $su ? \&Sub::Util::set_subname
            : $sn ? \&Sub::Name::subname
            : sub { $_[1] };

$::sub_name_calls = 0;
my $code = $subname->('Example::named', sub { 5 });

is($code->(), 5, 'selected subname implementation returns the code reference');
is($::sub_name_calls, 1,
    'loading List::Util does not bypass a later Sub::Util require');

done_testing;
