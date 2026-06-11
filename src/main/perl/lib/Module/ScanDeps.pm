package Module::ScanDeps;

use strict;
use warnings;
use Exporter ();

our $VERSION = '1.37';
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(scan_deps);

my %SKIP = map { $_ => 1 } qw(
    base constant feature integer lib parent strict utf8 vars warnings
);

sub _module_to_file {
    my $module = shift;
    return unless defined $module && $module =~ /\A[A-Za-z_]\w*(?:::\w*)*\z/;
    $module =~ s!::!/!g;
    return "$module.pm";
}

sub _find_in_inc {
    my $file = shift;
    return $file if defined $file && -f $file;
    for my $dir (@INC) {
        next if ref $dir;
        my $path = "$dir/$file";
        return $path if -f $path;
    }
    return undef;
}

sub _record {
    my ($deps, $module_file, $source_file) = @_;
    return unless defined $module_file;
    my $file = _find_in_inc($module_file);
    return unless defined $file;
    $deps->{$module_file} ||= {
        file    => $file,
        type    => 'module',
        used_by => [],
    };
    push @{ $deps->{$module_file}{used_by} }, $source_file
        if defined $source_file;
}

sub _scan_file {
    my ($deps, $file) = @_;
    open my $fh, '<', $file or return;
    while (my $line = <$fh>) {
        $line =~ s/#.*//;
        while ($line =~ /\b(?:use|no)\s+([A-Za-z_]\w*(?:::\w*)*)\b/g) {
            my $module = $1;
            next if $SKIP{$module};
            _record($deps, _module_to_file($module), $file);
        }
        while ($line =~ /\brequire\s+([A-Za-z_]\w*(?:::\w*)*)\b/g) {
            _record($deps, _module_to_file($1), $file);
        }
        while ($line =~ /\brequire\s+['"]([^'"]+\.pm)['"]/g) {
            _record($deps, $1, $file);
        }
    }
    close $fh;
}

sub scan_deps {
    my %args = @_ == 1 && ref($_[0]) eq 'HASH' ? %{ $_[0] } : @_;
    my $files = $args{files} || [];
    my $recurse = $args{recurse} || 0;
    my %deps;
    my @queue = ref($files) eq 'ARRAY' ? @$files : ($files);
    my %seen;

    while (@queue) {
        my $file = shift @queue;
        next unless defined $file && !$seen{$file}++ && -f $file;
        my %before = %deps;
        _scan_file(\%deps, $file);
        next unless $recurse;
        for my $key (keys %deps) {
            next if exists $before{$key};
            push @queue, $deps{$key}{file} if defined $deps{$key}{file};
        }
    }

    return \%deps;
}

1;
