use strict;
use warnings;
use Config;
use File::Temp qw(tempdir);
use Test::More;
BEGIN {
    unless ($Config{useithreads}) {
        print "1..0 # SKIP system Perl does not support ithreads\n";
        exit 0;
    }
}
use threads;

my $lib_dir = tempdir(CLEANUP => 1);
mkdir "$lib_dir/Issue2043" or die "mkdir $lib_dir/Issue2043: $!";
open my $module, '>', "$lib_dir/Issue2043/ThreadedImportedCV.pm"
    or die "open generated module: $!";
print {$module} <<'MODULE';
package Issue2043::ThreadedImportedCV;
use strict;
use warnings;
use Scalar::Util qw/refaddr weaken/;
use namespace::clean;

{
    my %seek_and_destroy;

    sub register_reference {
        weaken($seek_and_destroy{refaddr($_[0])} = $_[0]);
        return 1;
    }
}

1;
MODULE
close $module or die "close generated module: $!";

unshift @INC, $lib_dir;
require Issue2043::ThreadedImportedCV;

my $main_result = eval {
    Issue2043::ThreadedImportedCV::register_reference({ imported_cv => 1 });
};
my $main_error = $@;
ok(!$main_error && $main_result,
    'main interpreter can call the imported CV after runtime module cleanup');
diag($main_error) if $main_error;

my $thread = threads->create(sub {
    my $result = eval {
        Issue2043::ThreadedImportedCV::register_reference({ imported_cv => 1 });
    };
    return [$result, $@];
});
my ($result, $error) = @{ $thread->join };
ok(!$error && $result,
    'thread can call an imported Scalar::Util CV after runtime module cleanup');
diag($error) if $error;
done_testing;
