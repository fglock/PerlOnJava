use strict;
use warnings;
use Test::More;

package Local::SymbolicImporter::Hash;
sub TIEHASH { bless {}, shift }
sub FETCH { $_[1] eq 'imported' ? 42 : undef }
sub STORE { }
sub FIRSTKEY { 'imported' }
sub NEXTKEY { undef }
sub EXISTS { $_[1] eq 'imported' }
sub CLEAR { }

package Local::SymbolicImporter;
our %provided_hash;
BEGIN { tie %provided_hash, 'Local::SymbolicImporter::Hash' }
sub provided_sub { 'imported sub' }
sub import {
    my $pkg = shift;
    my ($caller) = caller;
    my @symbols = @_;
    no strict 'refs';
    for my $symbol (@symbols) {
        $symbol =~ s/^([\$\&\@\%])?//;
        my $prefix = $1 || '&';
        my $imported = "${caller}::$symbol";
        my $source = "${pkg}::$symbol";
        if ($prefix eq '%') {
            *{$imported} = \%{$source};
        } else {
            *{$imported} = \&{$source};
        }
    }
}

package main;
BEGIN { Local::SymbolicImporter->import('%provided_hash', 'provided_sub') }

is($provided_hash{imported}, 42,
    'a custom typeglob importer makes an imported hash visible under strict');
is(ref(tied %provided_hash), 'Local::SymbolicImporter::Hash',
    'the custom importer preserves the tied hash alias');
is(provided_sub(), 'imported sub',
    'a custom typeglob importer makes an imported sub visible under strict');

done_testing;
