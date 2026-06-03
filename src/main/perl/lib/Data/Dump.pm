package Data::Dump;

use strict;
use warnings;
use subs qw(dump);
use Exporter qw(import);
use Scalar::Util qw(blessed reftype);

our $VERSION = '1.25';
our $DEBUG;
our @FILTERS;
our @EXPORT = qw(dd ddx);
our @EXPORT_OK = qw(dump pp dumpf quote);

$DEBUG = 0;

sub dump {
    my @dumped = map { _dump_filtered($_) } @_;
    my $out = @dumped == 0 ? '()'
            : @dumped == 1 ? $dumped[0]
            : '(' . join(', ', @dumped) . ')';
    print STDERR "$out\n" unless defined wantarray;
    return $out;
}

sub _dump_filtered {
    my ($value) = @_;

    if (@FILTERS) {
        require Data::Dump::FilterContext;

        my $is_ref = ref($value) ? 1 : 0;
        my $object = $is_ref ? $value : \$value;
        my $class = blessed($object) || '';
        my $type = reftype($object) || ($is_ref ? ref($object) : 'SCALAR');
        my $ctx = Data::Dump::FilterContext->new(
            $object, $class, $type, $is_ref, undef, undef, []
        );

        for my $filter (@FILTERS) {
            my $filtered = $filter->($ctx, $object) or next;
            if (exists $filtered->{dump}) {
                return $filtered->{dump};
            }
            if (exists $filtered->{object}) {
                local @FILTERS;
                return _dump_filtered($filtered->{object});
            }
        }
    }

    return _dump_plain($value);
}

sub _dump_plain {
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

sub dumpf {
    require Data::Dump::Filtered;
    goto &Data::Dump::Filtered::dump_filtered;
}

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

sub fullname {
    my ($top, $idx) = @_;
    return '$' . $top . join('', @$idx);
}

1;
