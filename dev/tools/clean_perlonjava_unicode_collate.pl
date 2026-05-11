#!/usr/bin/env perl
use strict;
use warnings;
use File::Path qw(remove_tree);
use File::Spec;

=head1 NAME

clean_perlonjava_unicode_collate.pl - Remove a shadowing Unicode::Collate jcpan install

=head1 SYNOPSIS

  perl dev/tools/clean_perlonjava_unicode_collate.pl
  perl dev/tools/clean_perlonjava_unicode_collate.pl --dry-run

=head1 DESCRIPTION

If C<Unicode::Collate> was installed under F<~/.perlonjava/lib> (e.g. as a
dependency of another CPAN module), that copy can override the JAR-bundled
F<Unicode/Collate.pm> that is patched for PerlOnJava. A vanilla F<.pm> enables
the XS trie path and calls C<_fetch_rest>, which the JVM does not provide,
leading to:

  Undefined subroutine &Unicode::Collate::_fetch_rest called ...

This script removes the user-lib install so the bundled tree is used again.
It does not touch the copy inside the PerlOnJava JAR.

=cut

my $dry = grep { $_ eq '--dry-run' || $_ eq '-n' } @ARGV;

my $home = $ENV{HOME} // '';
die "HOME is not set; cannot locate ~/.perlonjava/lib\n" if $home eq '';

my $lib = File::Spec->catdir($home, '.perlonjava', 'lib');
my @paths = (
    File::Spec->catfile($lib, 'Unicode', 'Collate.pm'),
    File::Spec->catdir($lib, 'Unicode', 'Collate'),
    File::Spec->catdir($lib, 'auto', 'Unicode', 'Collate'),
);

print "PerlOnJava: cleaning shadow Unicode::Collate under $lib\n";

for my $p (@paths) {
    next unless -e $p;
    if ($dry) {
        print "  [dry-run] would remove: $p\n";
        next;
    }
    if (-d $p) {
        print "  removing directory: $p\n";
        remove_tree($p) or warn "  warning: remove_tree $p: $!\n";
    }
    else {
        print "  removing file: $p\n";
        unlink $p or warn "  warning: unlink $p: $!\n";
    }
}

if ($dry) {
    print "Dry-run only; pass without --dry-run to delete.\n";
}
else {
    print "Done. Re-run your jperl/jcpan command.\n";
}
