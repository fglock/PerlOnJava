package Data::Dump;

use strict;
use warnings;
use subs qw(dump);
use Exporter qw(import);

our $VERSION = '1.25';
our @EXPORT = qw(dd ddx);
our @EXPORT_OK = qw(dump pp dumpf quote);

sub dump {
    require Data::Dumper;
    my $dumper = Data::Dumper->new([@_]);
    $dumper->Terse(1);
    $dumper->Indent(1);
    $dumper->Sortkeys(1);
    my $out = $dumper->Dump;
    chomp $out;
    return $out;
}

sub pp { dump(@_) }
sub dumpf { dump(@_) }

sub dd {
    print dump(@_), "\n";
}

sub ddx {
    my (undef, $file, $line) = caller;
    $file =~ s{.*[\\/]}{};
    my $out = "$file:$line: " . dump(@_) . "\n";
    $out =~ s/^/# /gm;
    print $out;
}

sub quote {
    my $str = defined $_[0] ? $_[0] : '';
    $str =~ s/\\/\\\\/g;
    $str =~ s/"/\\"/g;
    $str =~ s/\n/\\n/g;
    return qq{"$str"};
}

1;
