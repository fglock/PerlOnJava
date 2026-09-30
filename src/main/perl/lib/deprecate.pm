package deprecate;
use strict;
use warnings;
our $VERSION = 0.04;

# our %Config can ignore %Config::Config, e.g. for testing
our %Config;
unless (%Config) { require Config; *Config = \%Config::Config; }

# This isn't a public API. It's internal to code maintained by the perl-porters
# If you would like it to be a public API, please send a patch with
# documentation and tests. Until then, it may change without warning.
sub __loaded_from_core {
    my ($package, $file, $expect_leaf) = @_;

    foreach my $pair ([qw(sitearchexp archlibexp)],
		      [qw(sitelibexp privlibexp)]) {
	my ($site, $priv) = @Config{@$pair};
	if ($^O eq 'VMS') {
	    for my $d ($site, $priv) { $d = VMS::Filespec::unixify($d) };
	}
	# Just in case anyone managed to configure with trailing /s
	s!/*$!!g foreach $site, $priv;

	next if $site eq $priv;
	my $core_file = "$priv/$expect_leaf";
	# caller() may expose only a normalized compilation-unit name on the JVM;
	# %INC retains the spelling selected by require's @INC search.
	my @loaded_names = ($file, $INC{$expect_leaf});
	# The JVM loader may preserve an absolute resolved filename while %Config
	# (and core's test override) uses a relative directory spelling.
	for my $loaded (@loaded_names) {
	    next unless defined $loaded;
	    if (uc($core_file) eq uc($loaded)
		|| uc($loaded) =~ /\Q$core_file\E\z/) {
		return 1;
	    }
	}
    }
    return 0;
}

sub import {
    my ($package, $file) = caller;

    my $expect_leaf = "$package.pm";
    $expect_leaf =~ s!::!/!g;

    if (__loaded_from_core($package, $file, $expect_leaf)) {
	my $call_depth=1;
	my @caller;
	while (@caller = caller $call_depth++) {
	    last if $caller[7]			# use/require
		and $caller[6] eq $expect_leaf;	# the package file
	}
	unless (@caller) {
	    # The JVM backend currently omits caller()[7]'s require marker for
	    # compilation-unit calls.  The next frame is nevertheless the loading
	    # source, and retains its lexical warnings bits and source location.
	    # Frame 1 is the imported module and frame 2 is the JVM's require
	    # compilation-unit shim; the source site which issued require is frame 3.
	    @caller = caller 3;
	    @caller = caller 2 unless @caller;
	    @caller = caller 1 unless @caller;
	}
	unless (@caller) {
	    require Carp;
	    Carp::cluck(<<"EOM");
Can't find use/require $expect_leaf in caller stack
EOM
	    return;
	}

	# This is fragile, because it
	# is directly poking in the internals of warnings.pm
	my ($call_file, $call_line, $callers_bitmask) = @caller[1,2,9];

	# The JVM caller stack currently loses caller()[9] while crossing the
	# require compilation-unit boundary.  This branch is reached only for a
	# core installation, so retain Perl's deprecation notice rather than
	# silently suppressing it because that transient bitmask is absent.
	if (!defined $callers_bitmask
	    || vec($callers_bitmask, $warnings::Offsets{deprecated}, 1)
	    || vec($callers_bitmask, $warnings::Offsets{all}, 1)) {
	    warn <<"EOM";
$package will be removed from the Perl core distribution in the next major release. Please install it from CPAN. It is being used at $call_file, line $call_line.
EOM
	}
    }
}

1;
