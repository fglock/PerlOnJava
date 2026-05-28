package Module::Load::Util;

use strict;
use warnings;
use Exporter qw(import);

our $VERSION = '0.012';
our @EXPORT_OK = qw(
    load_module_with_optional_args
    instantiate_class_with_optional_args
    call_module_function_with_optional_args
    call_module_method_with_optional_args
);

sub _normalize_module_with_optional_args {
    my ($spec) = @_;
    my ($module, $args);

    if (ref($spec) eq 'ARRAY') {
        die "array form or module/class name must have 1 or 2 elements"
            unless @$spec == 1 || @$spec == 2;
        ($module, $args) = @$spec;
        $args ||= [];
        $args = [%$args] if ref($args) eq 'HASH';
        die "In array form of module/class name, the 2nd element must be arrayref or hashref"
            unless ref($args) eq 'ARRAY';
    }
    elsif (ref($spec)) {
        die "module/class name must be string or 2-element array";
    }
    elsif ($spec =~ /(.+?)[=,](.*)/) {
        ($module, my $argstr) = ($1, $2);
        $args = [split /,/, $argstr];
    }
    else {
        $module = $spec;
        $args = [];
    }

    $module =~ /\A[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*\z/
        or die "Invalid syntax in module/class name '$module'";
    return ($module, $args);
}

sub _load_module {
    my $opts = ref($_[0]) eq 'HASH' ? shift : {};
    my $module = shift;
    my $do_load = exists $opts->{load} ? $opts->{load} : 1;

    my @prefixes = $opts->{ns_prefixes} ? @{ $opts->{ns_prefixes} } :
        defined($opts->{ns_prefix}) ? ($opts->{ns_prefix}) : ('');
    @prefixes = ('') unless @prefixes;

    for my $prefix (@prefixes) {
        my $candidate = length($prefix)
            ? $prefix . ($prefix =~ /::\z/ ? '' : '::') . $module
            : $module;
        return $candidate unless $do_load;

        (my $pm = "$candidate.pm") =~ s{::}{/}g;
        my $ok = eval { require $pm; 1 };
        return $candidate if $ok;
        die $@ unless $opts->{ns_prefixes} && $@ =~ /\ACan't locate/;
    }

    die "load_module_with_optional_args(): Failed to load module '$module'";
}

sub load_module_with_optional_args {
    my $opts = ref($_[0]) eq 'HASH' ? shift : {};
    my ($module, $args) = _normalize_module_with_optional_args(shift);
    my $loaded = _load_module($opts, $module);

    my $do_import = exists $opts->{import} ? $opts->{import} : 1;
    if ($do_import) {
        my $target = $opts->{target_package} || $opts->{caller} || caller(0);
        $target =~ /\A[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*\z/
            or die "Invalid syntax in target package '$target'";
        my $code = "package $target; ${loaded}->import(\\\@{\$args});";
        eval $code;
        die $@ if $@;
    }

    return { module => $loaded, args => $args };
}

sub instantiate_class_with_optional_args {
    my $opts = ref($_[0]) eq 'HASH' ? { %{ shift() } } : {};
    $opts->{import} = 0;
    $opts->{target_package} = caller(0);
    my $res = load_module_with_optional_args($opts, shift);
    return $res unless exists($opts->{construct}) ? $opts->{construct} : 1;
    my $constructor = defined $opts->{constructor} ? $opts->{constructor} : 'new';
    return $res->{module}->$constructor(@{ $res->{args} });
}

sub call_module_function_with_optional_args {
    my $opts = ref($_[0]) eq 'HASH' ? shift : {};
    my ($module, $args) = _normalize_module_with_optional_args(shift);
    my $function = $opts->{function};
    if (!defined $function) {
        $module =~ s/\A(.+)::(\w+)\z/$1/ or die "Please specify MODULE::FUNCTION";
        $function = $2;
    }
    my $loaded = _load_module($opts, $module);
    no strict 'refs';
    return &{"${loaded}::$function"}(@$args);
}

sub call_module_method_with_optional_args {
    my $opts = ref($_[0]) eq 'HASH' ? shift : {};
    my ($module, $args) = _normalize_module_with_optional_args(shift);
    my $method = $opts->{method};
    if (!defined $method) {
        $module =~ s/\A(.+)::(\w+)\z/$1/ or die "Please specify MODULE::METHOD";
        $method = $2;
    }
    my $loaded = _load_module($opts, $module);
    return $loaded->$method(@$args);
}

1;
