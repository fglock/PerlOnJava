package PerlOnJava::ProviderManifest;

use strict;
use warnings;
use File::Spec ();
use JSON::PP ();

our $VERSION = '1.0';

my $manifest;
my $providers_by_module;

sub _manifest_path {
    my ($volume, $directory) = File::Spec->splitpath(__FILE__);
    my $adjacent = File::Spec->catpath($volume, $directory, 'providers.json');
    return $adjacent if -f $adjacent;
    for my $inc (@INC) {
        my $candidate = File::Spec->catfile($inc, 'PerlOnJava', 'providers.json');
        return $candidate if -f $candidate;
    }
    die "PerlOnJava bundled-provider manifest is missing from \@INC\n";
}

sub manifest {
    return $manifest if $manifest;

    my $path = _manifest_path();
    open my $fh, '<', $path or die "Cannot read provider manifest $path: $!\n";
    local $/;
    my $json = <$fh>;
    close $fh or die "Cannot close provider manifest $path: $!\n";

    $manifest = JSON::PP::decode_json($json);
    die "Unsupported provider-manifest schema\n"
        unless ref($manifest) eq 'HASH'
            && $manifest->{schema_version} == 1
            && ref($manifest->{providers}) eq 'ARRAY';

    my %valid_provider = map { $_ => 1 }
        qw(bundled-perl java-xs compatibility-shim unsupported-native);
    my %valid_shadow = map { $_ => 1 } qw(forbidden allowed);
    my %seen;
    for my $entry (@{ $manifest->{providers} }) {
        die "Invalid provider-manifest entry\n" unless ref($entry) eq 'HASH';
        for my $field (qw(module version distribution provider shadow_policy test_strategy)) {
            die "Provider-manifest entry is missing $field\n"
                unless defined($entry->{$field}) && length($entry->{$field});
        }
        die "Duplicate bundled provider for $entry->{module}\n" if $seen{$entry->{module}}++;
        die "Invalid provider type $entry->{provider}\n"
            unless $valid_provider{$entry->{provider}};
        die "Invalid shadow policy $entry->{shadow_policy}\n"
            unless $valid_shadow{$entry->{shadow_policy}};
    }
    return $manifest;
}

sub providers {
    return @{ manifest()->{providers} };
}

sub provider_for {
    my ($class, $module) = @_;
    return unless defined $module;
    unless ($providers_by_module) {
        my %index = map { $_->{module} => $_ } $class->providers;
        $providers_by_module = \%index;
    }
    return $providers_by_module->{$module};
}

1;

__END__

=head1 NAME

PerlOnJava::ProviderManifest - bundled module provider metadata

=head1 DESCRIPTION

Loads the machine-readable C<PerlOnJava/providers.json> manifest without
loading any provided module. CPAN uses this metadata to satisfy compatible
prerequisites and to prevent user-local installs from shadowing authoritative
bundled providers.

=cut
