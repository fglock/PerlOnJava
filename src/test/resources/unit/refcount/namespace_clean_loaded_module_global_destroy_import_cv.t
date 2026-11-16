use strict;
use warnings;
use File::Temp qw(tempdir);

my $lib_dir = tempdir(CLEANUP => 1);
mkdir "$lib_dir/Issue2043" or die "mkdir $lib_dir/Issue2043: $!";
open my $module, '>', "$lib_dir/Issue2043/GlobalImportedCV.pm"
    or die "open generated module: $!";
print {$module} <<'MODULE';
package Issue2043::GlobalImportedCV;
use strict;
use warnings;
use Scalar::Util qw(refaddr);
use namespace::clean;

our $destructor_calls = 0;
our $last_imported_call_succeeded = 0;
sub DESTROY {
    my $address;
    my $ok = eval { $address = refaddr($_[0]); 1 };
    $last_imported_call_succeeded = $ok && $address;
    ++$destructor_calls;
    if ($destructor_calls == 2) {
        print $last_imported_call_succeeded
            ? "ok 2 - imported CV remains callable during global destruction\n"
            : "not ok 2 - imported CV remains callable during global destruction\n";
    }
}
sub imported_call_status {
    return ($destructor_calls, $last_imported_call_succeeded);
}

1;
MODULE
close $module or die "close generated module: $!";

unshift @INC, $lib_dir;
require Issue2043::GlobalImportedCV;
print "1..2\n";
my $first = bless {}, 'Issue2043::GlobalImportedCV';
undef $first;
my ($destructor_calls, $first_call_succeeded) =
    Issue2043::GlobalImportedCV::imported_call_status();
print $destructor_calls == 1 && $first_call_succeeded
    ? "ok 1 - imported CV remains callable after namespace cleanup\n"
    : "not ok 1 - imported CV remains callable after namespace cleanup\n";

# Global hash values are destroyed after global CV cleanup. Keep a second
# instance there to exercise the imported call from DESTROY at process exit.
our %global_objects;
$global_objects{shutdown} = bless {}, 'Issue2043::GlobalImportedCV';
